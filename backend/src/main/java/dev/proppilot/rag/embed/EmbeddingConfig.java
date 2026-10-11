package dev.proppilot.rag.embed;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.config.EmbeddingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the client from the validated settings, so a padded or upper-case EMBEDDING_PROVIDER selects the same client
 * that {@link EmbeddingProperties} accepted: a condition on the raw property would not.
 */
@Configuration
class EmbeddingConfig {

    @Bean
    EmbeddingClient embeddingClient(EmbeddingProperties props, ObjectMapper json) {
        if (props.provider().equals(EmbeddingProperties.VOYAGE)) {
            return new VoyageEmbeddingClient(props, json);
        }
        return new NoEmbeddingClient(props);
    }
}
