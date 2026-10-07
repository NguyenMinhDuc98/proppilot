package dev.proppilot.agent;

import com.fasterxml.jackson.databind.JsonNode;

/** Progress notifications emitted while the agent works; the chat layer turns them into SSE events. */
public sealed interface AgentEvent {

    record ToolCall(String name, JsonNode args) implements AgentEvent {
    }

    record ToolResultEvent(String name, String summary, boolean error) implements AgentEvent {
    }

    record Token(String text) implements AgentEvent {
    }
}
