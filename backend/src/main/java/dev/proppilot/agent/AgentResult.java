package dev.proppilot.agent;

import dev.proppilot.agent.llm.Usage;
import dev.proppilot.agent.routing.ModelRoute;
import dev.proppilot.guardrail.GuardrailOutcome;
import java.util.List;

/**
 * What a run produced. {@code errorCode} is set only when the status is {@code ERROR}; {@code ABORTED} means the
 * client went away, and the usage is what had been spent by then.
 *
 * @param llmCallRecords every model call the run made, in order, whatever became of the run
 * @param toolCallRecords every tool execution of the run, in order
 * @param route where the run's last answered call went: the model that answered and why it was chosen, with how the
 *              question scored. The route the run was sent to when no call was answered, as a model that failed
 *              answered nothing
 * @param guardrail what the grounding guardrail found in the answer, or null when it did not check one: the guardrail
 *                  is off, or the run did not end with an answer
 */
public record AgentResult(String answer, Usage usage, int toolCalls, int iterations, Status status, ErrorCode errorCode,
                          List<LlmCallRecord> llmCallRecords, List<ToolCallRecord> toolCallRecords, ModelRoute route,
                          GuardrailOutcome guardrail) {

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
                toolCallRecords, route, null);
    }
}
