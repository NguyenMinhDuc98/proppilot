package dev.proppilot.rag.embed;

import dev.proppilot.agent.llm.LlmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Gives every stored chunk an embedding from the configured model: the chunks that have none and the chunks whose
 * embedding came from another model. It goes in batches, and each batch is stored as soon as it comes back, so a run
 * that stops early keeps what it did and the next run carries on from there.
 *
 * <p>Embedding is an extra, never a requirement. A failure of the provider (a rate limit, the network, a bad key)
 * stops the run at the first batch that fails, after the client's own retries; the chunks stay without an embedding
 * and keyword search finds them as before. A run also stops when it has used up {@link #TIME_LIMIT}. Nothing here
 * throws because of the provider, so a run cannot hold back the application.
 */
@Component
public class EmbeddingBackfill {

    static final Duration TIME_LIMIT = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfill.class);

    /**
     * @param embedded chunks that got an embedding in this run
     * @param failed chunks that needed one and still have none
     */
    public record Result(int embedded, int failed) {
        static final Result NOTHING = new Result(0, 0);
    }

    private record Pending(long id, String text) {
    }

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddings;
    private final Clock clock;

    public EmbeddingBackfill(JdbcTemplate jdbc, EmbeddingClient embeddings, Clock clock) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.clock = clock;
    }

    /** Safe to call at any time and as often as wanted: with nothing to embed it makes no call to the provider. */
    public Result run() {
        if (!embeddings.enabled()) {
            return Result.NOTHING;
        }
        var deadline = clock.instant().plus(TIME_LIMIT);
        int embedded = 0;
        var batch = pending(0);
        while (!batch.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                return stopped(embedded, "it was interrupted");
            }
            if (!clock.instant().isBefore(deadline)) {
                return stopped(embedded, "it ran out of time");
            }
            try {
                store(batch, embeddings.embedDocuments(batch.stream().map(Pending::text).toList()));
            } catch (LlmException e) {
                return stopped(embedded, "the provider failed: " + e.getMessage() + ", " + e.code());
            }
            embedded += batch.size();
            batch = pending(batch.get(batch.size() - 1).id());
        }
        if (embedded > 0) {
            log.info("Embedded {} lease chunks with {}", embedded, embeddings.model());
        }
        return new Result(embedded, 0);
    }

    private Result stopped(int embedded, String reason) {
        int failed = jdbc.queryForObject("""
                select count(*) from lease_chunks
                where embedding is null or embedding_model is distinct from ?""", Integer.class, embeddings.model());
        log.warn("Embedded {} lease chunks with {} and stopped with {} left: {}", embedded, embeddings.model(), failed,
                reason);
        return new Result(embedded, failed);
    }

    /**
     * The next chunks after the given id that need an embedding, with the text to embed: the title of the document in
     * front of the chunk, which begins with its clause heading. A chunk alone says "30 days" and not whose.
     */
    private List<Pending> pending(long afterId) {
        return jdbc.query("""
                select c.id, d.title || chr(10) || c.content
                from lease_chunks c join lease_documents d on d.id = c.document_id
                where c.id > ? and (c.embedding is null or c.embedding_model is distinct from ?)
                order by c.id
                limit ?""", (rs, row) -> new Pending(rs.getLong(1), rs.getString(2)), afterId, embeddings.model(),
                EmbeddingClient.MAX_BATCH);
    }

    private void store(List<Pending> batch, List<float[]> vectors) {
        var rows = new ArrayList<Object[]>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            rows.add(new Object[] {VectorLiteral.of(vectors.get(i)), embeddings.model(), batch.get(i).id()});
        }
        jdbc.batchUpdate("update lease_chunks set embedding = ?::vector, embedding_model = ? where id = ?", rows);
    }
}
