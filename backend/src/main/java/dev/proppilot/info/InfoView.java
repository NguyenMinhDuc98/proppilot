package dev.proppilot.info;

/** What the UI may know about the model behind the assistant. {@code mode} is "claude" or "offline". */
public record InfoView(String mode, String provider, String model) {
}
