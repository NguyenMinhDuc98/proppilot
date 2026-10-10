package dev.proppilot.actions;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long> {

    List<AuditEntry> findAllByOrderByOccurredAtDescIdDesc(Limit limit);

    List<AuditEntry> findAllByActionIdOrderByOccurredAtDescIdDesc(UUID actionId);
}
