package dev.proppilot.agent.llm;

import java.time.Duration;
import java.util.List;

/**
 * {@code timeout} is the time this call may take in total, retries and streaming the reply included; clients also
 * apply their own limit.
 *
 * @param model the model that is to answer, or null for the client's own. A client that has only one model ignores it
 * @param retryTimeouts whether the client may try a call that timed out again. False when another model takes the call
 *                      next: a model that stalled is likely to stall again, and the retries would use the time the
 *                      other model needs
 */
public record LlmRequest(String system, List<Message> messages, List<ToolSpec> tools, Duration timeout, String model,
                         boolean retryTimeouts) {

    public LlmRequest(String system, List<Message> messages, List<ToolSpec> tools, Duration timeout, String model) {
        this(system, messages, tools, timeout, model, true);
    }

    public LlmRequest(String system, List<Message> messages, List<ToolSpec> tools, Duration timeout) {
        this(system, messages, tools, timeout, null);
    }
}
