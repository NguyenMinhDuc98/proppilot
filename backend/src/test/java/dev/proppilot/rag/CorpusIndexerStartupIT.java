package dev.proppilot.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.rag.embed.FakeEmbeddingClient;
import dev.proppilot.rag.embed.FakeEmbeddingConfig;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** With embeddings on, the application writes the documents and then embeds their chunks by itself. */
@SpringBootTest(properties = "proppilot.rag.seed-on-startup=true")
@Import(FakeEmbeddingConfig.class)
class CorpusIndexerStartupIT extends PostgresIntegrationTest {

    static {
        // Documents that earlier tests left are taken away before the application starts, so that only its own
        // startup can write and embed them. On the first run there are no tables yet and nothing to take away.
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.execute("delete from lease_documents");
        } catch (SQLException noTablesYet) {
            // Flyway creates them when the application starts.
        }
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    FakeEmbeddingClient fake;

    @AfterEach
    void leaveNoEmbeddings() {
        // Other tests expect the shared corpus to have none.
        jdbc.update("update lease_chunks set embedding = null, embedding_model = null");
    }

    @Test
    void theChunksAreEmbeddedInTheBackgroundAfterTheDocumentsAreWritten() {
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
            int chunks = jdbc.queryForObject("select count(*) from lease_chunks", Integer.class);
            int embedded = jdbc.queryForObject(
                    "select count(*) from lease_chunks where embedding_model = ?", Integer.class, fake.model());
            assertThat(chunks).isGreaterThan(30 * 12);
            assertThat(embedded).isEqualTo(chunks);
        });
        assertThat(fake.documentCalls).isNotEmpty();
    }
}
