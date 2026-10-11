package dev.proppilot.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proppilot.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** What migrations V5 (the corpus tables) and V6 (leases that end soon) leave in the database. */
class CorpusMigrationsIT extends PostgresIntegrationTest {

    /** The migration date may be a midnight behind the day the assertions run on. */
    private static final String ENDS_SOON = "end_date between CURRENT_DATE + 9 and CURRENT_DATE + 60";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theVectorAndTrigramExtensionsAreInstalled() {
        var extensions = jdbc.queryForList("select extname from pg_extension", String.class);

        assertThat(extensions).contains("vector", "pg_trgm");
    }

    @Test
    void chunksAreIndexedForVectorFullTextAndTrigramSearch() {
        var indexes = jdbc.queryForList(
                "select indexdef from pg_indexes where tablename = 'lease_chunks'", String.class);

        assertThat(indexes).anyMatch(def -> def.contains("USING hnsw (embedding vector_cosine_ops)"));
        assertThat(indexes).anyMatch(def -> def.contains("USING gin (fts)"));
        assertThat(indexes).anyMatch(def -> def.contains("USING gin (search_text gin_trgm_ops)"));
    }

    @Test
    @Transactional
    void aChunkIsFoundByEnglishAndArabicWordsAndByTrigramSimilarity() {
        long documentId = insertDocument();
        insertChunk(documentId, "late payment fee ten percent of the rent غرامة التأخير عشرة بالمائة من الإيجار");

        assertThat(matching("fts @@ plainto_tsquery('english', 'payment fees')")).isEqualTo(1);
        assertThat(matching("fts @@ plainto_tsquery('arabic', 'غرامة')")).isEqualTo(1);
        assertThat(matching("'late paymnt fee' <% search_text")).isEqualTo(1);
        assertThat(matching("fts @@ plainto_tsquery('english', 'pets')")).isZero();
    }

    @Test
    @Transactional
    void anEmbeddingHas1024DimensionsAndIsOptional() {
        long documentId = insertDocument();
        insertChunk(documentId, "no embedding yet");

        jdbc.update("update lease_chunks set embedding = array_fill(0.5::real, ARRAY[1024])::vector, "
                + "embedding_model = 'voyage-4-lite' where document_id = ?", documentId);

        assertThat(jdbc.queryForObject("select vector_dims(embedding) from lease_chunks where document_id = ?",
                Integer.class, documentId)).isEqualTo(1024);
        assertThatThrownBy(() -> jdbc.update(
                "update lease_chunks set embedding = '[1,2,3]'::vector where document_id = ?", documentId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void sevenLeasesEndBetweenTenAndSixtyDaysFromTheMigrationDate() {
        assertThat(count("select count(*) from leases where " + ENDS_SOON)).isEqualTo(7);
        assertThat(count("select count(*) from leases where end_date < CURRENT_DATE + 9")).isZero();
    }

    @Test
    void fiveOfThemAreAmongTheFirstThirtyActiveLeasesAndTwoAreOutsideThem() {
        var firstThirty = jdbc.queryForList("select id from leases where active order by id limit 30", Long.class);
        var expiring = jdbc.queryForList("select id from leases where " + ENDS_SOON + " order by id", Long.class);

        assertThat(expiring).hasSize(7);
        assertThat(expiring.stream().filter(firstThirty::contains)).hasSize(5);
        assertThat(expiring.stream().filter(id -> !firstThirty.contains(id))).hasSize(2);
    }

    @Test
    void theExpiringLeasesAreSpreadOverTheWindowAndNoOtherLeaseIsTouched() {
        var days = jdbc.queryForList(
                "select end_date - CURRENT_DATE from leases where " + ENDS_SOON + " order by 1", Integer.class);

        assertThat(days).doesNotHaveDuplicates();
        assertThat(days.get(days.size() - 1) - days.get(0)).isGreaterThanOrEqualTo(30);
        assertThat(count("select count(distinct end_date) from leases where not (" + ENDS_SOON + ")")).isEqualTo(1);
        assertThat(count("select count(distinct start_date) from leases")).isEqualTo(1);
        assertThat(count("select count(*) from leases where not (" + ENDS_SOON + ") "
                + "and end_date < CURRENT_DATE + interval '12 months'")).isZero();
    }

    private long insertDocument() {
        long leaseId = jdbc.queryForObject("select max(id) from leases", Long.class);
        return jdbc.queryForObject("""
                insert into lease_documents (lease_id, code, title, language, page_count, generator_version, pdf)
                values (?, 'LSE-TEST-1', 'Test', 'en', 1, 1, '\\x25504446'::bytea) returning id""", Long.class, leaseId);
    }

    private void insertChunk(long documentId, String content) {
        jdbc.update("""
                insert into lease_chunks (document_id, ref, ordinal, page, content, search_text, token_estimate)
                values (?, 'LSE-TEST-1#0', 0, 1, ?, ?, 10)""", documentId, content, content.toLowerCase());
    }

    private int matching(String condition) {
        return count("select count(*) from lease_chunks where ref = 'LSE-TEST-1#0' and " + condition);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
