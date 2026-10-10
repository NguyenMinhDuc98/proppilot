package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The proposals the agent made and the decisions people take on them. Anyone may decide: the demo has no accounts. */
@RestController
@RequestMapping("/api/actions")
public class ActionController {

    static final int LATEST = 50;

    private final ActionService actions;
    private final ActionLimits limits;
    private final VisitorActors visitors;

    public ActionController(ActionService actions, ActionLimits limits, VisitorActors visitors) {
        this.actions = actions;
        this.limits = limits;
        this.visitors = visitors;
    }

    @GetMapping
    public List<ActionView> latest() {
        return actions.latest(LATEST);
    }

    @GetMapping("/{id}")
    public ActionView get(@PathVariable String id) {
        return actions.get(ActionIds.parse(id));
    }

    /** Recomputes the diff, optionally with the edits an approval would carry. Changes nothing. */
    @PostMapping("/{id}/preview")
    public PreviewView preview(@PathVariable String id, @RequestBody(required = false) JsonNode body,
                               HttpServletRequest http) {
        limits.requireDecisionAllowed(http.getRemoteAddr());
        return actions.preview(ActionIds.parse(id), edits(body));
    }

    @PostMapping("/{id}/approve")
    public ApprovalView approve(@PathVariable String id,
                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                @RequestBody(required = false) JsonNode body, HttpServletRequest http) {
        limits.requireDecisionAllowed(http.getRemoteAddr());
        return actions.approve(ActionIds.parse(id), idempotencyKey, edits(body), visitors.actorFor(http.getRemoteAddr()));
    }

    @PostMapping("/{id}/reject")
    public ActionView reject(@PathVariable String id, @RequestBody(required = false) JsonNode body,
                             HttpServletRequest http) {
        limits.requireDecisionAllowed(http.getRemoteAddr());
        return actions.reject(ActionIds.parse(id), reason(body), visitors.actorFor(http.getRemoteAddr()));
    }

    private static ObjectNode edits(JsonNode body) {
        if (body == null || body.isNull()) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (!body.isObject()) {
            throw new ActionException(ActionError.INVALID_REQUEST, "The request body must be a JSON object.");
        }
        return (ObjectNode) body;
    }

    private static String reason(JsonNode body) {
        var reason = edits(body).get("reason");
        if (reason == null || reason.isNull()) {
            return null;
        }
        if (!reason.isTextual()) {
            throw new ActionException(ActionError.INVALID_REQUEST, "reason must be text.");
        }
        return reason.asText();
    }
}
