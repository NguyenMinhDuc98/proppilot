package dev.proppilot.guardrail;

import java.util.List;

/**
 * What the guardrail found in the answer a run delivered.
 *
 * @param violations the values of that answer that could not be grounded; empty unless the status is a flagged one
 */
public record GuardrailOutcome(GuardrailStatus status, List<Violation> violations) {

    public GuardrailOutcome {
        violations = List.copyOf(violations);
    }

    public static GuardrailOutcome of(List<Violation> violations, boolean retried) {
        if (violations.isEmpty()) {
            return new GuardrailOutcome(retried ? GuardrailStatus.RETRIED_OK : GuardrailStatus.OK, violations);
        }
        return new GuardrailOutcome(retried ? GuardrailStatus.RETRIED_FLAGGED : GuardrailStatus.FLAGGED, violations);
    }

    public boolean flagged() {
        return !violations.isEmpty();
    }
}
