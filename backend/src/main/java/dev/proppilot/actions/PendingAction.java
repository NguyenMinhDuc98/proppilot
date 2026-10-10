package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A change the agent proposed. It is written to the business tables only after a person approves it. */
@Entity
@Table(name = "pending_actions")
@Getter
@NoArgsConstructor
public class PendingAction {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    private ActionKind kind;

    @Enumerated(EnumType.STRING)
    private ActionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode args;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode preview;

    private String summaryEn;
    private String summaryAr;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant decidedAt;
    private String decisionReason;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode result;

    private String idempotencyKey;
    private Instant confirmedAt;
    private UUID runId;
    private String actor;

    public PendingAction(UUID id, ActionKind kind, Plan plan, Instant createdAt, Instant expiresAt, UUID runId) {
        this.id = id;
        this.kind = kind;
        this.status = ActionStatus.PROPOSED;
        this.args = plan.args();
        this.preview = plan.preview();
        this.summaryEn = plan.summaryEn();
        this.summaryAr = plan.summaryAr();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.runId = runId;
    }

    public boolean isDueForExpiry(Instant now) {
        return status == ActionStatus.PROPOSED && !now.isBefore(expiresAt);
    }

    /** A person changed the proposal before approving it. */
    void edit(Plan plan) {
        requireProposed();
        this.args = plan.args();
        this.preview = plan.preview();
        this.summaryEn = plan.summaryEn();
        this.summaryAr = plan.summaryAr();
    }

    void approve(Instant at, String actor, String idempotencyKey, JsonNode result) {
        decide(ActionStatus.APPROVED, at, actor);
        this.idempotencyKey = idempotencyKey;
        this.confirmedAt = at;
        this.result = result;
    }

    void fail(Instant at, String actor, String idempotencyKey, JsonNode failure, String reason) {
        decide(ActionStatus.FAILED, at, actor);
        this.idempotencyKey = idempotencyKey;
        this.confirmedAt = at;
        this.decisionReason = reason;
        this.result = failure;
    }

    void reject(Instant at, String actor, String reason) {
        decide(ActionStatus.REJECTED, at, actor);
        this.decisionReason = reason;
    }

    void expire(Instant at) {
        decide(ActionStatus.EXPIRED, at, null);
    }

    private void decide(ActionStatus to, Instant at, String actor) {
        requireProposed();
        this.status = to;
        this.decidedAt = at;
        this.actor = actor;
    }

    private void requireProposed() {
        if (status != ActionStatus.PROPOSED) {
            throw new IllegalStateException("Action " + id + " is already " + status);
        }
    }
}
