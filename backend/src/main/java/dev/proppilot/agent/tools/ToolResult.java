package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @param content JSON (or plain error text) handed back to the model
 * @param summary one short line shown to the user in the UI
 */
public record ToolResult(String content, String summary, boolean error) {

    public static ToolResult ok(ObjectMapper json, Object data, String summary) {
        return new ToolResult(json.valueToTree(data).toString(), summary, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, message, true);
    }
}
