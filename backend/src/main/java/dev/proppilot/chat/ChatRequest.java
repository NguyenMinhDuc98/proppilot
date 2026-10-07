package dev.proppilot.chat;

import dev.proppilot.agent.ChatTurn;
import java.util.List;

public record ChatRequest(String message, List<ChatTurn> history) {

    public List<ChatTurn> historyOrEmpty() {
        return history == null ? List.of() : history;
    }
}
