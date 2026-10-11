package dev.proppilot.chat;

import dev.proppilot.guardrail.ClaimKind;
import dev.proppilot.guardrail.Violation;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** A row of {@code run_guardrail_findings}: one claim of a stored run's answer that the guardrail could not ground. */
@Embeddable
public record RunGuardrailFinding(
        @Column(name = "seq") int sequence,
        @Enumerated(EnumType.STRING) ClaimKind kind,
        String claim) {

    static RunGuardrailFinding of(int sequence, Violation violation) {
        return new RunGuardrailFinding(sequence, violation.kind(), violation.value());
    }
}
