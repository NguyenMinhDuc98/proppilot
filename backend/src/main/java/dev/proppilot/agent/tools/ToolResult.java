package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @param content JSON (or plain error text) handed back to the model
 * @param summary one short line shown to the user in the UI
 * @param ui what the web app shows next to the answer, or null; it is never sent to the model
 * @param evidence what the result states about the data, which the grounding guardrail holds an answer against: the
 *                 {@code content}, less what the model itself wrote into the call and the app's own guidance, neither
 *                 of which makes a value true. Empty when the result states nothing
 */
public record ToolResult(String content, String summary, boolean error, JsonNode ui, String evidence) {

    /** The {@code type} of a {@code ui} that carries a proposed action under {@code action}. */
    public static final String ACTION_UI_TYPE = "action";

    /** The {@code type} of a {@code ui} that carries the lease clauses an answer rests on under {@code sources}. */
    public static final String SOURCES_UI_TYPE = "sources";

    public static ToolResult ok(ObjectMapper json, Object data, String summary) {
        var content = ToolJson.write(json, data);
        return new ToolResult(content, summary, false, null, content);
    }

    /** An error whose text may state data (the tenants that matched a name, the amount outstanding). */
    public static ToolResult error(String message) {
        return new ToolResult(message, message, true, null, message);
    }

    /**
     * An error for a call the app turned down or could not serve. Its text quotes what the model sent and says how a
     * call looks, so it states nothing about the data and is no evidence: a value that it repeats or gives as an
     * example is not thereby true.
     */
    public static ToolResult rejected(String message) {
        return new ToolResult(message, message, true, null, "");
    }

    public ToolResult withUi(JsonNode ui) {
        return new ToolResult(content, summary, error, ui, evidence);
    }

    public ToolResult withEvidence(String evidence) {
        return new ToolResult(content, summary, error, ui, evidence);
    }
}
