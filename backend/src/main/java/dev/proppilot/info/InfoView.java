package dev.proppilot.info;

/**
 * What the UI may know about the assistant behind the chat. {@code mode} is "claude" or "offline";
 * {@code maxHistoryItems} is the longest history the chat endpoint accepts.
 */
public record InfoView(String mode, String provider, String model, int maxHistoryItems) {
}
