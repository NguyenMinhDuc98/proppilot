package dev.proppilot.agent;

/** One earlier question or answer, sent back by the client so the agent has conversation context. */
public record ChatTurn(String role, String text) {

    public boolean fromUser() {
        return "user".equalsIgnoreCase(role);
    }
}
