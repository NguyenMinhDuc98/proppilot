package dev.proppilot.rag.embed;

import dev.proppilot.agent.llm.LlmException;
import java.util.List;

/**
 * Turns text into vectors, so lease clauses can be found by meaning as well as by words. A failed call throws an
 * {@link LlmException}: its code says why, and its message never carries provider text or the key.
 */
public interface EmbeddingClient {

    /** The most texts one call to {@link #embedDocuments} sends to the provider; a longer list is sent in parts. */
    int MAX_BATCH = 32;

    /** One vector for each text, in the order of the texts. Call it only when {@link #enabled()}. */
    List<float[]> embedDocuments(List<String> texts);

    /** The vector of a question. Call it only when {@link #enabled()}. */
    float[] embedQuery(String text);

    /** False when no provider is configured: nothing is embedded and search uses keywords only. */
    boolean enabled();

    /** The model the vectors come from, stored with them so a change of model can be told from a current vector. */
    String model();

    /** The length of every vector. */
    int dimensions();
}
