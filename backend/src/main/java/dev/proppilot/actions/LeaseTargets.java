package dev.proppilot.actions;

import dev.proppilot.agent.tools.TenantResolver;
import dev.proppilot.agent.tools.ToolArgs;
import dev.proppilot.domain.Lease;
import org.springframework.stereotype.Component;

/** Finds the lease an action is about by {@code unit_code} and/or {@code tenant_name}, with the agent tools' rules. */
@Component
class LeaseTargets {

    private final TenantResolver resolver;

    LeaseTargets(TenantResolver resolver) {
        this.resolver = resolver;
    }

    Lease resolve(ToolArgs args) {
        var resolution = resolver.resolve(args.string("tenant_name"), args.string("unit_code"));
        return switch (resolution) {
            case TenantResolver.Resolution.Found found -> found.lease();
            case TenantResolver.Resolution.Failed failed ->
                    throw new ActionException(errorFor(failed.problem()), failed.reason());
        };
    }

    private static ActionError errorFor(TenantResolver.Problem problem) {
        return switch (problem) {
            case NO_ACTIVE_LEASE -> ActionError.NO_ACTIVE_LEASE;
            case TENANT_MISMATCH -> ActionError.TENANT_UNIT_MISMATCH;
            case TENANT_NOT_FOUND -> ActionError.TENANT_NOT_FOUND;
            case AMBIGUOUS_TENANT -> ActionError.AMBIGUOUS_TENANT;
        };
    }
}
