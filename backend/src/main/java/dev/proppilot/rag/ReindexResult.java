package dev.proppilot.rag;

/**
 * What a reindex left behind.
 *
 * @param documents lease documents stored, once the run is over
 * @param chunks chunks stored, once the run is over
 * @param embedded chunks that got an embedding in this run
 * @param failed chunks that needed an embedding and still have none, for example when the provider is rate limited
 */
public record ReindexResult(int documents, int chunks, int embedded, int failed) {
}
