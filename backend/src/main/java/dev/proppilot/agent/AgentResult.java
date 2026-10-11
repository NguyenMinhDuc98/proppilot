package dev.proppilot.agent;

import dev.proppilot.agent.llm.Usage;
import dev.proppilot.agent.routing.ModelRoute;
import java.util.List;

/**
 * What a run produced. {@code errorCode} is set only when the status is {@code ERROR}; {@code ABORTED} means the
 * client went away, and the usage is what had been spent by then.
 *
 * @param llmCallRecords every model call the run made, in order, whatever became of the run
 * @param toolCallRecords every tool execution of the run, in order
 * @param route the model of the run's last call (or the one it was sent to, when it made none), why it went there and
 *              how the question scored
 */
public record AgentResult(String answer, Usage usage, int toolCalls, int iterations, Status status, ErrorCode errorCode,
                          List<LlmCallRecord> llmCallRecords, List<ToolCallRecord> toolCallRecords, ModelRoute route) {

    public enum Status { OK, ERROR, MAX_ITERATIONS, TRUNCATED, ABORTED }

    public AgentResult {
        llmCallRecords = List.copyOf(llmCallRecords);
        toolCallRecords = List.copyOf(toolCallRecords);
    }

    /** A failed run; the answer is the generic message for the code, never provider text. */
    public static AgentResult failed(ErrorCode code, Usage usage, int toolCalls, int iterations,
                                     List<LlmCallRecord> llmCallRecords, List<ToolCallRecord> toolCallRecords,
                                     ModelRoute route) {
        return new AgentResult(code.message(), usage, toolCalls, iterations, Status.ERROR, code, llmCallRecords,
                toolCallRecords, route);
    }
}
