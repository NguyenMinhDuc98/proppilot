package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @param content JSON (or plain error text) handed back to the model
 * @param summary one short line shown to the user in the UI
 * @param ui what the web app shows next to the answer, or null; it is never sent to the model
 */
public record ToolResult(String content, String summary, boolean error, JsonNode ui) {

    /** The {@code type} of a {@code ui} that carries a proposed action under {@code action}. */
    public static final String ACTION_UI_TYPE = "action";

    /** The {@code type} of a {@code ui} that carries the lease clauses an answer rests on under {@code sources}. */
    public static final String SOURCES_UI_TYPE = "sources";

    public ToolResult(String content, String summary, boolean error) {
        this(content, summary, error, null);
    }

    public static ToolResult ok(ObjectMapper json, Object data, String summary) {
        return new ToolResult(json.valueToTree(data).toString(), summary, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, message, true);
    }

    public ToolResult withUi(JsonNode ui) {
        return new ToolResult(content, summary, error, ui);
    }
}
