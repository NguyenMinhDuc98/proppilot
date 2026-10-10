package dev.proppilot.agent;

import java.util.List;
import java.util.UUID;

/**
 * One turn for the agent.
 *
 * @param message the user turn: a question, or the server's own account of a decision a person took
 * @param runId the chat run this turn is recorded as; tools tag their proposals with it
 * @param toolsOffered false for a confirmation: the model only words an outcome that is already settled, so it gets no
 *                     tools and cannot propose anything new
 */
public record AgentRequest(String message, List<ChatTurn> history, UUID runId, boolean toolsOffered) {

    public static AgentRequest question(String message, List<ChatTurn> history, UUID runId) {
        return new AgentRequest(message, history, runId, true);
    }

    public static AgentRequest confirmation(String message, List<ChatTurn> history, UUID runId) {
        return new AgentRequest(message, history, runId, false);
    }
}
