package dev.proppilot.agent;

import dev.proppilot.agent.llm.Usage;

/** What a run produced. {@code errorCode} is set only when the status is {@code ERROR}. */
public record AgentResult(String answer, Usage usage, int toolCalls, int iterations, Status status, ErrorCode errorCode) {

    public enum Status { OK, ERROR, MAX_ITERATIONS, TRUNCATED }

    public AgentResult(String answer, Usage usage, int toolCalls, int iterations, Status status) {
        this(answer, usage, toolCalls, iterations, status, null);
    }

    /** A failed run; the answer is the generic message for the code, never provider text. */
    public static AgentResult failed(ErrorCode code, Usage usage, int toolCalls, int iterations) {
        return new AgentResult(code.message(), usage, toolCalls, iterations, Status.ERROR, code);
    }
}
