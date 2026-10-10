package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/** Writes the audit log. Callers run inside their own transaction so an entry commits or rolls back with the change. */
@Component
public class AuditTrail {

    static final String AGENT = "agent";
    static final String SYSTEM = "system";
    static final String ACTION = "PENDING_ACTION";

    private final AuditEntryRepository entries;
    private final ObjectMapper json;
    private final Clock clock;

    AuditTrail(AuditEntryRepository entries, ObjectMapper json, Clock clock) {
        this.entries = entries;
        this.json = json;
        this.clock = clock;
    }

    void proposed(PendingAction action) {
        var after = json.createObjectNode();
        after.put("kind", action.getKind().name());
        after.set("args", action.getArgs());
        after.put("summaryEn", action.getSummaryEn());
        after.put("expiresAt", action.getExpiresAt().toString());
        record(AGENT, AuditEvent.PROPOSED, action.getId(), ACTION, action.getId().toString(), null, after, null);
    }

    void edited(PendingAction action, String actor, JsonNode before, JsonNode after) {
        record(actor, AuditEvent.EDITED, action.getId(), ACTION, action.getId().toString(), before, after, null);
    }

    /** The entry describes the business data that changed, not the action. */
    void approved(PendingAction action, String actor, ActionExecutor.Execution execution) {
        record(actor, AuditEvent.APPROVED, action.getId(), execution.entityType(), execution.entityId(),
                execution.before(), execution.after(), null);
    }

    void rejected(PendingAction action, String actor) {
        transition(AuditEvent.REJECTED, actor, action);
    }

    void expired(PendingAction action) {
        transition(AuditEvent.EXPIRED, SYSTEM, action);
    }

    void failed(PendingAction action, String actor) {
        transition(AuditEvent.FAILED, actor, action);
    }

    /** Every action starts out PROPOSED, so a transition always leaves that state. */
    private void transition(AuditEvent event, String actor, PendingAction action) {
        var before = json.createObjectNode().put("status", ActionStatus.PROPOSED.name());
        var after = json.createObjectNode().put("status", action.getStatus().name());
        record(actor, event, action.getId(), ACTION, action.getId().toString(), before, after, action.getDecisionReason());
    }

    public void reset(String actor, JsonNode counts) {
        record(actor, AuditEvent.RESET, null, "DEMO", "seed", null, counts, "Demo data restored to the seed");
    }

    public List<AuditEntry> latest(int limit) {
        return entries.findAllByOrderByOccurredAtDescIdDesc(Limit.of(limit));
    }

    /** What happened to one action, newest first: a proposal, at most one edit and one decision. */
    public List<AuditEntry> of(UUID actionId) {
        return entries.findAllByActionIdOrderByOccurredAtDescIdDesc(actionId);
    }

    private void record(String actor, AuditEvent event, UUID actionId, String entityType, String entityId,
                        JsonNode before, JsonNode after, String note) {
        entries.save(new AuditEntry(clock.instant(), actor, event, actionId, entityType, entityId, before, after, note));
    }
}
