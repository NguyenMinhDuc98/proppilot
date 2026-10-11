package dev.proppilot.config;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding settings. Provider "none" keeps the lease search to keywords: no key, no cost. Provider "voyage" calls the
 * Voyage AI embeddings API as well, so a question is also matched by meaning.
 *
 * @param dimensions the length of one embedding; it must be the size of the {@code lease_chunks.embedding} column
 * @param timeoutSeconds how long one request to the provider may take
 */
@ConfigurationProperties(prefix = "proppilot.embedding")
public record EmbeddingProperties(
        String provider,
        String apiKey,
        String model,
        int dimensions,
        String apiUrl,
        int timeoutSeconds,
        int maxRetries,
        int retryBaseDelayMs) {

    public static final String NONE = "none";
    public static final String VOYAGE = "voyage";
    /** The width of the {@code lease_chunks.embedding_model} column. */
    private static final int MAX_MODEL_LENGTH = 64;

    public EmbeddingProperties {
        // A variable that the host passes on empty (a dashboard value that was never filled in) means no embeddings.
        provider = provider == null || provider.isBlank() ? NONE : provider.strip().toLowerCase(Locale.ROOT);
        if (!provider.equals(NONE) && !provider.equals(VOYAGE)) {
            throw new IllegalArgumentException("EMBEDDING_PROVIDER must be " + NONE + " or " + VOYAGE);
        }
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null ? "" : model.strip();
        if (model.isEmpty() || model.length() > MAX_MODEL_LENGTH) {
            throw new IllegalArgumentException("EMBEDDING_MODEL must be 1 to " + MAX_MODEL_LENGTH + " characters");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("EMBEDDING_DIMENSIONS must be above 0");
        }
    }

    /** Keeps the key out of any log line or error message that prints the properties. */
    @Override
    public String toString() {
        return "EmbeddingProperties[provider=" + provider + ", model=" + model + ", dimensions=" + dimensions + "]";
    }
}
