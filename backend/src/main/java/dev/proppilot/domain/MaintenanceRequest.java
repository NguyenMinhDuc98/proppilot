package dev.proppilot.domain;

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

/** A repair or service request for a unit, opened by an approved action. */
@Entity
@Table(name = "maintenance_requests")
@Getter
@NoArgsConstructor
public class MaintenanceRequest {

    private static final String OPEN = "OPEN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long unitId;
    private String unitCode;

    @Enumerated(EnumType.STRING)
    private MaintenanceCategory category;

    @Enumerated(EnumType.STRING)
    private MaintenancePriority priority;

    private String description;
    private String status;
    private Instant createdAt;
    private UUID actionId;

    public MaintenanceRequest(Unit unit, MaintenanceCategory category, MaintenancePriority priority,
                              String description, Instant createdAt, UUID actionId) {
        this.unitId = unit.getId();
        this.unitCode = unit.getCode();
        this.category = category;
        this.priority = priority;
        this.description = description;
        this.status = OPEN;
        this.createdAt = createdAt;
        this.actionId = actionId;
    }
}
