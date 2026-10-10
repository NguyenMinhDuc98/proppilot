package dev.proppilot.actions;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Who did what to which record and when, with the data as it was and as it became. Only ever inserted. */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "at")
    private Instant occurredAt;

    private String actor;

    @Enumerated(EnumType.STRING)
    private AuditEvent event;

    private UUID actionId;
    private String entityType;
    private String entityId;

    @Column(name = "before")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode beforeState;

    @Column(name = "after")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode afterState;

    private String note;

    AuditEntry(Instant occurredAt, String actor, AuditEvent event, UUID actionId, String entityType, String entityId,
               JsonNode beforeState, JsonNode afterState, String note) {
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.event = event;
        this.actionId = actionId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.note = note;
    }
}
