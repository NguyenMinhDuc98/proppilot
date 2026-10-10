package dev.proppilot.actions;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PendingActionRepository extends JpaRepository<PendingAction, UUID> {

    List<PendingAction> findAllByOrderByCreatedAtDescIdDesc(Limit limit);

    boolean existsByIdempotencyKey(String idempotencyKey);

    long countByRunId(UUID runId);

    @Query("select a.id from PendingAction a where a.status = dev.proppilot.actions.ActionStatus.PROPOSED and a.expiresAt <= :now")
    List<UUID> findIdsDueForExpiry(@Param("now") Instant now);

    /**
     * Holds the row until the transaction ends, so two decisions on one action run one after the other and the second
     * sees what the first did.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PendingAction a where a.id = :id")
    Optional<PendingAction> findForUpdate(@Param("id") UUID id);
}
