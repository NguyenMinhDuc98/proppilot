package dev.proppilot.agent.llm;

import java.time.Duration;
import java.util.List;

/**
 * {@code timeout} is the time this call may take in total, retries and streaming the reply included; clients also
 * apply their own limit.
 *
 * @param model the model that is to answer, or null for the client's own. A client that has only one model ignores it
 */
public record LlmRequest(String system, List<Message> messages, List<ToolSpec> tools, Duration timeout, String model) {

    public LlmRequest(String system, List<Message> messages, List<ToolSpec> tools, Duration timeout) {
        this(system, messages, tools, timeout, null);
    }
}
