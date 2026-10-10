package dev.proppilot.agent.tools;

import java.util.UUID;

/**
 * What a tool call may know about the run it belongs to.
 *
 * @param runId the chat run that is calling the tool, or null for a call that is not part of a recorded run
 */
public record ToolContext(UUID runId) {

    public static final ToolContext NONE = new ToolContext(null);
}
