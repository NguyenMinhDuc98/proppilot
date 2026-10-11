package dev.proppilot.rag.embed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when EMBEDDING_DIMENSIONS is not the size of the {@code lease_chunks.embedding} column: every
 * vector would be rejected by the database, and every question would silently fall back to keywords.
 */
@Component
class EmbeddingColumnCheck implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddings;

    EmbeddingColumnCheck(JdbcTemplate jdbc, EmbeddingClient embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    @Override
    public void run(ApplicationArguments args) {
        // For a vector column the type modifier is its dimension.
        int column = jdbc.queryForObject("""
                select atttypmod from pg_attribute
                where attrelid = 'lease_chunks'::regclass and attname = 'embedding'""", Integer.class);
        if (column != embeddings.dimensions()) {
            throw new IllegalStateException("EMBEDDING_DIMENSIONS is " + embeddings.dimensions()
                    + " but the lease_chunks.embedding column holds " + column + " numbers: they must be equal");
        }
    }
}
