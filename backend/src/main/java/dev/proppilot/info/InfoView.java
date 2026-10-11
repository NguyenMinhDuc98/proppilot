package dev.proppilot.info;

import dev.proppilot.rag.retrieve.RetrievalMode;

/**
 * What the UI may know about the assistant behind the chat. {@code mode} is "claude" or "offline";
 * {@code maxHistoryItems} is the longest history the chat endpoint accepts and {@code maxHistoryTurnLength} the longest
 * text of one turn in it; {@code retrieval} is how the lease documents are searched, "hybrid" (keywords and meaning)
 * or "keyword"; {@code leaseDocuments} is how many are stored.
 */
public record InfoView(String mode, String provider, String model, int maxHistoryItems, int maxHistoryTurnLength,
                       RetrievalMode retrieval, int leaseDocuments) {
}
