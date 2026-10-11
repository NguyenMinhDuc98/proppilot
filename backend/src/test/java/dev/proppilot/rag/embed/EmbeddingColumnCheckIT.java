package dev.proppilot.rag.embed;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proppilot.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The configured vector length must be the column's, or the application does not start. */
class EmbeddingColumnCheckIT extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private static FakeEmbeddingClient clientWithDimensions(int dimensions) {
        return new FakeEmbeddingClient() {
            @Override
            public int dimensions() {
                return dimensions;
            }
        };
    }

    @Test
    void passesWhenTheDimensionsAreTheColumnsSize() {
        var check = new EmbeddingColumnCheck(jdbc, clientWithDimensions(1024));

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartWhenTheyDiffer() {
        var check = new EmbeddingColumnCheck(jdbc, clientWithDimensions(512));

        assertThatThrownBy(() -> check.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMBEDDING_DIMENSIONS is 512")
                .hasMessageContaining("lease_chunks.embedding column holds 1024");
    }
}
