package dev.proppilot.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.config.RagProperties;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The application writes the lease documents by itself, in the background, once it is ready. */
@SpringBootTest(properties = "proppilot.rag.seed-on-startup=true")
class LeaseDocumentStartupIT extends PostgresIntegrationTest {

    static {
        // Documents that earlier tests left are taken away before the application starts, so that only its own
        // startup can write them. On the first run there are no tables yet and nothing to take away.
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.execute("delete from lease_documents");
        } catch (SQLException noTablesYet) {
            // Flyway creates them when the application starts.
        }
    }

    @Autowired
    LeaseDocumentSeeder seeder;
    @Autowired
    RagProperties properties;

    @Test
    void theDocumentsAppearInTheBackgroundWithoutAnyoneAskingForThem() {
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            var counts = seeder.counts();
            assertThat(counts.documents()).isEqualTo(30);
            assertThat(counts.chunks()).isGreaterThan(30 * 12);
        });
    }

    @Test
    void theDefaultsAre30DocumentsAndSeedingOnStartup() {
        assertThat(properties.documentCount()).isEqualTo(30);
        assertThat(properties.seedOnStartup()).isTrue();
    }
}
