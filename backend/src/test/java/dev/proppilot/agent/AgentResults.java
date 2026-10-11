package dev.proppilot.agent;

import dev.proppilot.agent.llm.Usage;
import dev.proppilot.agent.routing.ModelRoute;
import java.util.List;

/** {@link AgentResult}s for the tests that store or show a run and do not run the agent. */
public final class AgentResults {

    private AgentResults() {
    }

    /** A run that answered, with no model call or tool call in its trace. */
    public static AgentResult answered(String model, Usage usage, int toolCalls, int iterations) {
        return ended(model, usage, toolCalls, iterations, AgentResult.Status.OK);
    }

    public static AgentResult ended(String model, Usage usage, int toolCalls, int iterations, AgentResult.Status status) {
        return new AgentResult("answer", usage, toolCalls, iterations, status, null, List.of(), List.of(),
                ModelRoute.byDefault(model), null);
    }
}
