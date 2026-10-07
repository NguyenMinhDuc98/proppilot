package dev.proppilot.agent;

import dev.proppilot.agent.llm.Usage;

public record AgentResult(String answer, Usage usage, int toolCalls, int iterations, Status status) {

    public enum Status { OK, ERROR, MAX_ITERATIONS }
}
