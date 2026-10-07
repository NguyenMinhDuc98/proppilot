package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/** A read-only capability the model can call. Implement as a Spring bean to register it automatically. */
public interface Tool {

    String name();

    String description();

    /** JSON Schema describing the input object. */
    JsonNode inputSchema();

    /** Throw {@link ToolInputException} for invalid input; the message is shown to the model. */
    ToolResult execute(JsonNode input);
}
