package dev.proppilot.agent;

import dev.proppilot.agent.llm.Usage;
import java.util.List;

/**
 * What a run produced. {@code errorCode} is set only when the status is {@code ERROR}; {@code ABORTED} means the
 * client went away, and the usage is what had been spent by then.
 *
 * @param llmCallRecords every model call the run made, in order, whatever became of the run
 * @param toolCallRecords every tool execution of the run, in order
 */
public record AgentResult(String answer, Usage usage, int toolCalls, int iterations, Status status, ErrorCode errorCode,
                          List<LlmCallRecord> llmCallRecords, List<ToolCallRecord> toolCallRecords) {

    public enum Status { OK, ERROR, MAX_ITERATIONS, TRUNCATED, ABORTED }

    public AgentResult {
        llmCallRecords = List.copyOf(llmCallRecords);
        toolCallRecords = List.copyOf(toolCallRecords);
    }

    public AgentResult(String answer, Usage usage, int toolCalls, int iterations, Status status, ErrorCode errorCode) {
        this(answer, usage, toolCalls, iterations, status, errorCode, List.of(), List.of());
    }

    public AgentResult(String answer, Usage usage, int toolCalls, int iterations, Status status) {
        this(answer, usage, toolCalls, iterations, status, null);
    }

    /** A failed run; the answer is the generic message for the code, never provider text. */
    public static AgentResult failed(ErrorCode code, Usage usage, int toolCalls, int iterations,
                                     List<LlmCallRecord> llmCallRecords, List<ToolCallRecord> toolCallRecords) {
        return new AgentResult(code.message(), usage, toolCalls, iterations, Status.ERROR, code, llmCallRecords,
                toolCallRecords);
    }
}
