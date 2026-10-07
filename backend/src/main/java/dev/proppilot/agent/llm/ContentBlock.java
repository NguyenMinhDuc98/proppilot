package dev.proppilot.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** One block of a message, mirroring the Anthropic Messages API content blocks. */
public sealed interface ContentBlock {

    record Text(String text) implements ContentBlock {
    }

    record ToolUse(String id, String name, JsonNode input) implements ContentBlock {
    }

    record ToolResult(String toolUseId, String content, boolean error) implements ContentBlock {
    }
}
