package dev.proppilot.rag.retrieve;

import dev.proppilot.rag.embed.EmbeddingClient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The embedding of a question, when there can be one. A question asked again is answered from a small cache, so the
 * same demo question does not spend the provider's rate limit twice. A failure is never cached and never thrown: the
 * caller searches by keywords instead.
 */
@Component
class QueryEmbedder {

    static final int CAPACITY = 256;

    private static final Logger log = LoggerFactory.getLogger(QueryEmbedder.class);

    private final EmbeddingClient embeddings;
    /** Least recently used first; every access is synchronised because a read reorders the entries. */
    private final Map<String, float[]> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(CAPACITY, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                    return size() > CAPACITY;
                }
            });

    QueryEmbedder(EmbeddingClient embeddings) {
        this.embeddings = embeddings;
    }

    /** Empty when embeddings are off or the provider failed. */
    Optional<float[]> embed(String question) {
        if (!embeddings.enabled()) {
            return Optional.empty();
        }
        var cached = cache.get(question);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            var vector = embeddings.embedQuery(question);
            cache.put(question, vector);
            return Optional.of(vector);
        } catch (RuntimeException e) {
            log.warn("The question could not be embedded, searching by keywords only: {}", e.getMessage());
            return Optional.empty();
        }
    }

    String model() {
        return embeddings.model();
    }
}
