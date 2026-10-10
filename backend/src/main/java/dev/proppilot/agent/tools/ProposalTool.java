package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool that proposes a change for a person to approve and changes nothing itself. It only stores the proposal, which
 * is a write, so the registry does not run it in a read-only transaction.
 */
public interface ProposalTool extends Tool {

    ToolResult propose(JsonNode input, ToolContext context);

    @Override
    default ToolResult execute(JsonNode input) {
        return propose(input, ToolContext.NONE);
    }
}
