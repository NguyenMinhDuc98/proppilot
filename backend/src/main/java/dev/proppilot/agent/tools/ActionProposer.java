package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proppilot.actions.ActionException;
import dev.proppilot.actions.ActionKind;
import dev.proppilot.actions.ActionService;
import dev.proppilot.actions.ActionStatus;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only way a tool changes anything: it stores a proposal through {@link ActionService#propose}. The model is told
 * the change is waiting for a person; the web app gets the full proposal to show an approval card.
 */
@Component
class ActionProposer {

    private static final String NOTE = "This is only a proposal. Nothing has changed yet: a person must approve it in "
            + "the app. Do not tell the user it was done; tell them it is waiting for their approval.";

    private final ActionService actions;
    private final ObjectMapper json;

    ActionProposer(ActionService actions, ObjectMapper json) {
        this.actions = actions;
        this.json = json;
    }

    /** @param arguments built by the tool from the fields it accepts, never the model's input as it came */
    ToolResult propose(ActionKind kind, ObjectNode arguments, ToolContext context) {
        try {
            var proposed = actions.propose(kind, arguments, context.runId());
            var data = new Proposed(proposed.id(), proposed.status(), proposed.summaryEn(), proposed.summaryAr(), NOTE);
            var ui = json.createObjectNode().put("type", ToolResult.ACTION_UI_TYPE);
            ui.set("action", ToolJson.tree(json, proposed));
            return ToolResult.ok(json, data, "Proposed, waiting for approval: " + proposed.summaryEn()).withUi(ui);
        } catch (ActionException refused) {
            return ToolResult.error(refused.getMessage());
        }
    }

    record Proposed(UUID actionId, ActionStatus status, String summaryEn, String summaryAr, String note) {
    }
}
