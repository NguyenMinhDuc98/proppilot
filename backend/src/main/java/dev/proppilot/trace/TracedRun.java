package dev.proppilot.trace;

import dev.proppilot.agent.AgentResult;
import dev.proppilot.agent.ErrorCode;
import dev.proppilot.agent.LlmCallRecord;
import dev.proppilot.agent.ToolCallRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a tracer is told about a finished run: identifiers, counts, timings and outcomes. The question, the answer and
 * what the tools were asked and said are not part of it, so a tracer cannot export them.
 *
 * @param errorCode why the run failed, or null
 * @param routedReason why the run went to the model it did, or null
 * @param guardrailStatus what the grounding guardrail concluded, or null
 * @param startedAt when the run began
 * @param latencyMs how long it took
 */
public record TracedRun(UUID runId, String provider, String model, AgentResult.Status status, ErrorCode errorCode,
                        String routedReason, String guardrailStatus, int iterations, int toolCalls, Instant startedAt,
                        long latencyMs, List<LlmCallRecord> llmCalls, List<Tool> tools) {

    /** A tool execution without its arguments and its result. */
    public record Tool(String name, boolean error, Instant startedAt, long latencyMs) {

        public static Tool of(ToolCallRecord call) {
            return new Tool(call.toolName(), call.error(), call.startedAt(), call.latencyMs());
        }
    }
}
