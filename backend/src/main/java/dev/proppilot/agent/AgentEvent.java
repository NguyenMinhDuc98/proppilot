package dev.proppilot.agent;

import com.fasterxml.jackson.databind.JsonNode;

/** Progress notifications emitted while the agent works; the chat layer turns them into SSE events. */
public sealed interface AgentEvent {

    record ToolCall(String name, JsonNode args) implements AgentEvent {
    }

    /** @param ui what the web app shows for the result; never part of what the model sees */
    record ToolResultEvent(String name, String summary, boolean error, JsonNode ui) implements AgentEvent {
    }

    record Token(String text) implements AgentEvent {
    }
}
