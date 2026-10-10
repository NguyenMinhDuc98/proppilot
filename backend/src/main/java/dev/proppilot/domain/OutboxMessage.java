package dev.proppilot.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A tenant message that was approved but is only stored: nothing in PropPilot delivers it. */
@Entity
@Table(name = "outbox_messages")
@Getter
@NoArgsConstructor
public class OutboxMessage {

    @Id
    private UUID id;
    private Long leaseId;
    private String unitCode;
    private String tenantNameEn;
    private String tenantNameAr;
    private String language;
    private String purpose;
    private String subject;
    private String body;
    private boolean delivered;
    private Instant createdAt;
    private UUID actionId;

    public OutboxMessage(UUID id, Lease lease, String language, String purpose, String subject, String body,
                         Instant createdAt, UUID actionId) {
        this.id = id;
        this.leaseId = lease.getId();
        this.unitCode = lease.getUnit().getCode();
        this.tenantNameEn = lease.getTenant().getNameEn();
        this.tenantNameAr = lease.getTenant().getNameAr();
        this.language = language;
        this.purpose = purpose;
        this.subject = subject;
        this.body = body;
        this.delivered = false;
        this.createdAt = createdAt;
        this.actionId = actionId;
    }
}
