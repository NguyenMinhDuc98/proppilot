package dev.proppilot.rag.retrieve;

import java.util.List;

/**
 * What a search found.
 *
 * @param retrieval {@code hybrid} when the question was also matched by meaning; {@code keyword} when embeddings are
 *                  off or the embedding of this question failed
 * @param chunks best first
 */
public record RetrievalResult(RetrievalMode retrieval, List<RetrievedChunk> chunks) {
}
