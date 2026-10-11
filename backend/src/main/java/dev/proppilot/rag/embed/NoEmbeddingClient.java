package dev.proppilot.rag.embed;

import dev.proppilot.config.EmbeddingProperties;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The default: no provider, so lease search runs on keywords alone at no cost. */
@Component
@ConditionalOnProperty(name = "proppilot.embedding.provider", havingValue = EmbeddingProperties.NONE,
        matchIfMissing = true)
public class NoEmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties props;

    public NoEmbeddingClient(EmbeddingProperties props) {
        this.props = props;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        throw disabled();
    }

    @Override
    public float[] embedQuery(String text) {
        throw disabled();
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String model() {
        return props.model();
    }

    @Override
    public int dimensions() {
        return props.dimensions();
    }

    private static IllegalStateException disabled() {
        return new IllegalStateException("No embedding provider is configured; check enabled() before embedding");
    }
}
