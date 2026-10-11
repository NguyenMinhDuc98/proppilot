package dev.proppilot.rag.retrieve;

import dev.proppilot.rag.DocumentLanguage;
import dev.proppilot.rag.embed.VectorLiteral;
import dev.proppilot.rag.retrieve.ReciprocalRankFusion.Fused;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hybrid search over the lease chunks. A question is looked up three ways, each giving its best
 * {@value #CANDIDATES_PER_LIST} chunks: by embedding, by full text search and by trigram similarity. The three lists
 * are fused by {@link ReciprocalRankFusion}.
 *
 * <p>Embeddings are an extra: when they are off, or the embedding of the question fails, the other two lists answer
 * alone and the result says {@code keyword}. What the question holds is only ever bound as a parameter, or reduced to
 * plain words by {@link QueryTerms} first, so no question can break a query.
 */
@Service
public class LeaseRetriever {

    public static final int MAX_QUERY_LENGTH = 500;
    public static final int DEFAULT_TOP_K = 4;
    public static final int MAX_TOP_K = 8;

    static final int CANDIDATES_PER_LIST = 20;
    /** How alike, from 0 to 1, a word of the question and a stretch of a chunk must be to count as a match. */
    static final String TRIGRAM_THRESHOLD = "0.3";

    private static final String SCOPE_MARK = "{scope}";

    /**
     * Nearest first by cosine distance, among the chunks embedded by the configured model. The scan is exact on
     * purpose: the nearest-neighbour index looks at a few dozen candidates of the whole table and only then applies
     * the lease filter, which can leave a lease with none of its own chunks among them. The corpus is a few hundred
     * rows, so reading all of them costs nothing.
     */
    private static final String BY_EMBEDDING = """
            with scoped as materialized (
                select c.id, d.code, c.ordinal, c.embedding <=> ?::vector as distance
                from lease_chunks c join lease_documents d on d.id = c.document_id
                where c.embedding_model = ? and c.embedding is not null {scope}
            )
            select id, code, ordinal from scoped order by distance, code, ordinal limit ?""";

    private static final String BY_FULL_TEXT = """
            select c.id, d.code, c.ordinal
            from lease_chunks c join lease_documents d on d.id = c.document_id,
                 (select to_tsquery('arabic', ?) || to_tsquery('english', ?) as query) q
            where c.fts @@ q.query {scope}
            order by ts_rank_cd(c.fts, q.query, 1) desc, d.code, c.ordinal
            limit ?""";

    /** {@code <%} is true where the query is similar to a stretch of the chunk, as far as the threshold says. */
    private static final String BY_TRIGRAMS = """
            select c.id, d.code, c.ordinal, word_similarity(?::text, c.search_text) as similarity
            from lease_chunks c join lease_documents d on d.id = c.document_id
            where ?::text <% c.search_text {scope}
            order by similarity desc, d.code, c.ordinal
            limit ?""";

    private static final String CHUNKS_IN_ORDER = """
            select c.ref, d.code, d.title, u.code, t.name_en, t.name_ar, d.language, c.section_no, c.section_title,
                   c.page, c.content, c.id
            from lease_chunks c
            join lease_documents d on d.id = c.document_id
            join leases l on l.id = d.lease_id
            join units u on u.id = l.unit_id
            join tenants t on t.id = l.tenant_id
            where c.id = any (?::bigint[])
            order by array_position(?::bigint[], c.id)""";

    /** The leases a search is limited to: the SQL that does it, and the array it binds. */
    private record Scope(String sql, List<Object> args) {
        static final Scope ALL = new Scope("", List.of());

        static Scope of(Set<Long> leaseIds) {
            if (leaseIds == null) {
                return ALL;
            }
            return new Scope("and d.lease_id = any (?::bigint[])", List.of(array(leaseIds)));
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final QueryEmbedder queryEmbedder;

    LeaseRetriever(JdbcTemplate jdbc, PlatformTransactionManager transactions, QueryEmbedder queryEmbedder) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.queryEmbedder = queryEmbedder;
    }

    public RetrievalResult retrieve(String question, Set<Long> leaseIds) {
        return retrieve(question, leaseIds, DEFAULT_TOP_K);
    }

    /**
     * @param question what to look for; stripped, cut to {@value #MAX_QUERY_LENGTH} characters, and not blank
     * @param leaseIds the leases to search, or null for all of them; an empty set finds nothing
     * @param topK how many chunks to return, kept between 1 and {@value #MAX_TOP_K}
     * @throws IllegalArgumentException when the question is blank
     */
    public RetrievalResult retrieve(String question, Set<Long> leaseIds, int topK) {
        return search(question, leaseIds, topK, false);
    }

    /**
     * The best chunks of each lease on its own, so that a question about several leases is answered for every one of
     * them and not for the few whose documents happen to match best. The question is embedded once.
     *
     * @param perLease how many chunks to return for each lease, kept between 1 and {@value #MAX_TOP_K}
     * @param leaseIds the leases to search, in the order their chunks are returned
     */
    public RetrievalResult retrievePerLease(String question, Set<Long> leaseIds, int perLease) {
        return search(question, leaseIds, perLease, true);
    }

    private RetrievalResult search(String question, Set<Long> leaseIds, int topK, boolean eachLease) {
        var text = capped(question);
        if (leaseIds != null && leaseIds.isEmpty()) {
            return new RetrievalResult(RetrievalMode.KEYWORD, List.of());
        }
        var terms = QueryTerms.of(text);
        // Before the transaction: no database connection is held while a provider is waited for.
        var embedding = queryEmbedder.embed(text);
        var scopes = eachLease && leaseIds != null
                ? leaseIds.stream().map(id -> Scope.of(Set.of(id))).toList()
                : List.of(Scope.of(leaseIds));
        int limit = Math.clamp(topK, 1, MAX_TOP_K);
        var chunks = readOnly.execute(status -> scopes.stream()
                .flatMap(scope -> searchIn(scope, terms, embedding, limit).stream()).toList());
        return new RetrievalResult(RetrievalMode.of(embedding.isPresent()), chunks);
    }

    private List<RetrievedChunk> searchIn(Scope scope, QueryTerms terms, Optional<float[]> embedding, int limit) {
        var lists = new EnumMap<CandidateList, List<Candidate>>(CandidateList.class);
        embedding.ifPresent(vector -> lists.put(CandidateList.VECTOR, byEmbedding(vector, scope)));
        if (!terms.isEmpty()) {
            lists.put(CandidateList.FULL_TEXT, byFullText(terms, scope));
            lists.put(CandidateList.TRIGRAM, byTrigrams(terms, scope));
        }
        return chunks(ReciprocalRankFusion.fuse(lists, limit));
    }

    private static String capped(String question) {
        var text = question == null ? "" : question.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("The question must not be blank");
        }
        if (text.length() > MAX_QUERY_LENGTH) {
            boolean splitsAPair = Character.isHighSurrogate(text.charAt(MAX_QUERY_LENGTH - 1));
            text = text.substring(0, splitsAPair ? MAX_QUERY_LENGTH - 1 : MAX_QUERY_LENGTH).strip();
        }
        return text;
    }

    private List<Candidate> byEmbedding(float[] embedding, Scope scope) {
        return jdbc.query(BY_EMBEDDING.replace(SCOPE_MARK, scope.sql()), this::candidate,
                args(scope, VectorLiteral.of(embedding), queryEmbedder.model()));
    }

    private List<Candidate> byFullText(QueryTerms terms, Scope scope) {
        return jdbc.query(BY_FULL_TEXT.replace(SCOPE_MARK, scope.sql()), this::candidate,
                args(scope, terms.arabicTsQuery(), terms.otherTsQuery()));
    }

    /** The threshold is local to the transaction the search runs in, so no other query sees it. */
    private List<Candidate> byTrigrams(QueryTerms terms, Scope scope) {
        jdbc.queryForObject("select set_config('pg_trgm.word_similarity_threshold', ?, true)", String.class,
                TRIGRAM_THRESHOLD);
        return jdbc.query(BY_TRIGRAMS.replace(SCOPE_MARK, scope.sql()), this::candidate,
                args(scope, terms.text(), terms.text()));
    }

    /** The parameters of a candidate query: those before the scope, the scope's, and the size of the list. */
    private static Object[] args(Scope scope, Object... beforeScope) {
        var args = new ArrayList<Object>(List.of(beforeScope));
        args.addAll(scope.args());
        args.add(CANDIDATES_PER_LIST);
        return args.toArray();
    }

    private Candidate candidate(ResultSet rs, int row) throws SQLException {
        return new Candidate(rs.getLong(1), rs.getString(2), rs.getInt(3));
    }

    /** The chunks of the fused candidates, in their order. A chunk that was replaced in the meantime is left out. */
    private List<RetrievedChunk> chunks(List<Fused> fused) {
        if (fused.isEmpty()) {
            return List.of();
        }
        var byId = fused.stream().collect(Collectors.toMap(hit -> hit.candidate().id(), Function.identity()));
        var inOrder = array(fused.stream().map(hit -> hit.candidate().id()).toList());
        return jdbc.query(CHUNKS_IN_ORDER, (rs, row) -> {
            var hit = byId.get(rs.getLong(12));
            return new RetrievedChunk(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), DocumentLanguage.fromCode(rs.getString(7)),
                    rs.getObject(8, Integer.class), rs.getString(9), rs.getInt(10), rs.getString(11), hit.score(),
                    hit.lists());
        }, inOrder, inOrder);
    }

    /** A PostgreSQL array literal of numbers, which holds nothing a parameter could be broken by. */
    private static String array(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }
}
