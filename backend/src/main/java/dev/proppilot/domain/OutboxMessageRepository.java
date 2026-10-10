package dev.proppilot.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {
}
