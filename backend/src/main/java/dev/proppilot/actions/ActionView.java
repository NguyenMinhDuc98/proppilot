package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** A proposal as the API shows it. The idempotency key stays server-side. */
public record ActionView(
        UUID id,
        ActionKind kind,
        ActionStatus status,
        JsonNode args,
        JsonNode preview,
        String summaryEn,
        String summaryAr,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        String decisionReason,
        JsonNode result,
        Instant confirmedAt,
        UUID runId,
        String actor) {

    static ActionView of(PendingAction a) {
        return new ActionView(a.getId(), a.getKind(), a.getStatus(), a.getArgs(), a.getPreview(), a.getSummaryEn(),
                a.getSummaryAr(), a.getCreatedAt(), a.getExpiresAt(), a.getDecidedAt(), a.getDecisionReason(),
                a.getResult(), a.getConfirmedAt(), a.getRunId(), a.getActor());
    }
}
