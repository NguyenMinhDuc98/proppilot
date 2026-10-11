package dev.proppilot.admin;

import dev.proppilot.agent.AgentResult;
import dev.proppilot.chat.ChatRun;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.data.jpa.domain.Specification;

final class RunSpecifications {

    private RunSpecifications() {
    }

    /**
     * Every filter that is given must match. A run counts as flagged when the guardrail recorded at least one
     * violation for it; {@code before} is an exclusive upper bound on the creation time, for paging backwards.
     */
    static Specification<ChatRun> matching(AgentResult.Status status, String model, Boolean guardrailFlagged,
                                           Instant before) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (model != null && !model.isBlank()) {
                predicates.add(cb.equal(root.get("model"), model));
            }
            if (guardrailFlagged != null) {
                var violations = root.<Integer>get("guardrailViolations");
                predicates.add(guardrailFlagged ? cb.greaterThan(violations, 0) : cb.equal(violations, 0));
            }
            if (before != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), before));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
