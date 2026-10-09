package dev.proppilot.agent.llm;

import java.time.Duration;
import java.util.List;

/**
 * {@code timeout} is the time this call may take in total, retries and streaming the reply included; clients also
 * apply their own limit.
 */
public record LlmRequest(String system, List<Message> messages, List<ToolSpec> tools, Duration timeout) {
}
