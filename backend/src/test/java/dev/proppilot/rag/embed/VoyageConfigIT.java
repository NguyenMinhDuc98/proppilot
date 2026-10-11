package dev.proppilot.rag.embed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.proppilot.PostgresIntegrationTest;
import dev.proppilot.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the application as it runs against Voyage (with a dummy key and an address nothing listens on, so nothing can
 * reach the real API) and checks the documented defaults.
 */
@SpringBootTest(properties = {"proppilot.embedding.provider=voyage", "proppilot.embedding.api-key=test-key",
        "proppilot.embedding.api-url=http://127.0.0.1:9/v1/embeddings", "proppilot.rag.seed-on-startup=false"})
class VoyageConfigIT extends PostgresIntegrationTest {

    @Autowired
    EmbeddingClient embeddings;
    @Autowired
    EmbeddingProperties props;

    @Test
    void wiresTheVoyageClientWithTheDocumentedDefaults() {
        assertThat(embeddings).isInstanceOf(VoyageEmbeddingClient.class);
        assertThat(embeddings.enabled()).isTrue();
        assertThat(props.model()).isEqualTo("voyage-4-lite");
        assertThat(props.dimensions()).isEqualTo(1024);
        assertThat(props.timeoutSeconds()).isEqualTo(20);
        assertThat(props.maxRetries()).isEqualTo(3);
        assertThat(props.retryBaseDelayMs()).isEqualTo(1000);
    }
}
