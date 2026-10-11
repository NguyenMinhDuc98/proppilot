package dev.proppilot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What a tool returns for a tenant that could not be picked, and what of it is evidence. */
class TenantResolverFailureTest {

    private static ToolResult resultOf(TenantResolver.Problem problem) {
        return new TenantResolver.Resolution.Failed(problem, "reason of " + problem).toolResult();
    }

    @Test
    void everyProblemIsAnErrorWithItsReasonAsTheText() {
        for (var problem : TenantResolver.Problem.values()) {
            var result = resultOf(problem);

            assertThat(result.error()).isTrue();
            assertThat(result.content()).isEqualTo("reason of " + problem);
        }
    }

    @Test
    void aNameThatSeveralTenantsShareAndATenantOfAnotherUnitNameTenantsAndSoAreEvidence() {
        assertThat(resultOf(TenantResolver.Problem.AMBIGUOUS_TENANT).evidence())
                .isEqualTo("reason of AMBIGUOUS_TENANT");
        assertThat(resultOf(TenantResolver.Problem.TENANT_MISMATCH).evidence()).isEqualTo("reason of TENANT_MISMATCH");
    }

    @Test
    void aUnitWithoutALeaseAndANameNobodyHasOnlyRepeatWhatTheModelSent() {
        assertThat(resultOf(TenantResolver.Problem.NO_ACTIVE_LEASE).evidence()).isEmpty();
        assertThat(resultOf(TenantResolver.Problem.TENANT_NOT_FOUND).evidence()).isEmpty();
    }
}
