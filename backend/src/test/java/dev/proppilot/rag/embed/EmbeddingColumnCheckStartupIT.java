package dev.proppilot.rag.embed;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.ProppilotApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** The column check is part of startup: an application with the wrong dimensions does not come up. */
class EmbeddingColumnCheckStartupIT {

    @Test
    void theApplicationDoesNotStartWithDimensionsThatAreNotTheColumnsSize() {
        var postgres = PostgresIntegrationTest.POSTGRES;
        var application = new SpringApplicationBuilder(ProppilotApplication.class).web(WebApplicationType.NONE);

        // Command line arguments, because they outrank the application.yml that names a local database.
        assertThatThrownBy(() -> application.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--proppilot.rag.seed-on-startup=false",
                "--proppilot.embedding.dimensions=512"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMBEDDING_DIMENSIONS is 512")
                .hasMessageContaining("lease_chunks.embedding column holds 1024");
    }
}
