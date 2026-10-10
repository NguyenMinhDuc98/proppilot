package dev.proppilot.chat;

import dev.proppilot.agent.ChatTurn;
import java.util.List;
import java.util.UUID;

/**
 * @param message the question; not used when {@code actionId} is set, because the server then writes the turn itself
 * @param actionId a proposed action that a person has decided on, for the assistant to confirm in the chat
 */
public record ChatRequest(String message, List<ChatTurn> history, UUID actionId) {

    public List<ChatTurn> historyOrEmpty() {
        return history == null ? List.of() : history;
    }
}
