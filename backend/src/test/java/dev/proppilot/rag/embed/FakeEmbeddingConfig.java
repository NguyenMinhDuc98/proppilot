package dev.proppilot.rag.embed;

import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Puts {@link FakeEmbeddingClient} in place of the configured embedding client, so a test can run the whole pipeline
 * on real pgvector with embeddings switched on and no provider. Import it with {@code @Import}.
 *
 * <p>The words "relinquish" and "vacate" mean "termination" to the fake. No lease document contains either, so a
 * question that uses them can only be answered by meaning.
 */
@TestConfiguration
public class FakeEmbeddingConfig {

    @Bean
    @Primary
    FakeEmbeddingClient fakeEmbeddingClient() {
        return new FakeEmbeddingClient(Map.of("relinquish", "termination", "vacate", "termination"));
    }
}
