package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A capability the model can call: it reads data, or it is a {@link ProposalTool} that proposes a change for a person
 * to approve. No tool changes business data itself. Implement as a Spring bean to register it automatically.
 */
public interface Tool {

    String name();

    String description();

    /** JSON Schema describing the input object. */
    JsonNode inputSchema();

    /** Throw {@link ToolInputException} for invalid input; the message is shown to the model. */
    ToolResult execute(JsonNode input);
}
