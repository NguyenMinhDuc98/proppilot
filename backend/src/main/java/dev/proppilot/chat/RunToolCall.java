package dev.proppilot.chat;

import dev.proppilot.agent.ToolCallRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * A row of {@code run_tool_calls}: one tool execution of a stored run.
 *
 * @param llmSequence the {@code seq} of the model call that asked for the tool; null for a row stored before the
 *                    column existed
 */
@Embeddable
public record RunToolCall(
        @Column(name = "seq") int sequence,
        @Column(name = "llm_seq") Integer llmSequence,
        String toolName,
        String args,
        String summary,
        boolean error,
        long latencyMs) {

    static RunToolCall of(ToolCallRecord call) {
        return new RunToolCall(call.sequence(), call.llmSequence(), call.toolName(), call.args(), call.summary(),
                call.error(), call.latencyMs());
    }
}
