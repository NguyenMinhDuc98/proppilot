package dev.proppilot.guardrail;

import java.util.List;

/** {@link Guardrail}s for tests: a roster of the tenants a test names, and a mode. */
public final class Guardrails {

    private Guardrails() {
    }

    public static Guardrail off() {
        return of(GuardrailMode.OFF);
    }

    public static Guardrail of(GuardrailMode mode, TenantNames.Name... tenants) {
        return new Guardrail(mode, new GroundingChecker(new TenantNames(() -> List.of(tenants))));
    }
}
