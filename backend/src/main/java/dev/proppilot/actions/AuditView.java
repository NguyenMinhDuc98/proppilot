package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record AuditView(Long id, Instant at, String actor, AuditEvent event, UUID actionId, String entityType,
                        String entityId, JsonNode before, JsonNode after, String note) {

    static AuditView of(AuditEntry e) {
        return new AuditView(e.getId(), e.getOccurredAt(), e.getActor(), e.getEvent(), e.getActionId(),
                e.getEntityType(), e.getEntityId(), e.getBeforeState(), e.getAfterState(), e.getNote());
    }
}
