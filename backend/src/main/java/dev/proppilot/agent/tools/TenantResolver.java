package dev.proppilot.agent.tools;

import dev.proppilot.domain.Lease;
import dev.proppilot.domain.LeaseRepository;
import dev.proppilot.domain.Tenant;
import dev.proppilot.domain.TenantRepository;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Finds the single active lease a tool call is talking about, by unit code and/or tenant name. */
@Component
public class TenantResolver {

    private static final int MAX_CANDIDATES = 5;
    static final Pattern UNIT_CODE = Pattern.compile("^[A-Za-z]-\\d{3}$");

    private final LeaseRepository leases;
    private final TenantRepository tenants;

    TenantResolver(LeaseRepository leases, TenantRepository tenants) {
        this.leases = leases;
        this.tenants = tenants;
    }

    public enum Problem { NO_ACTIVE_LEASE, TENANT_MISMATCH, TENANT_NOT_FOUND, AMBIGUOUS_TENANT }

    /** Either a lease or why none could be picked: a stable {@link Problem} and a human-readable reason (for the model). */
    public sealed interface Resolution {
        record Found(Lease lease) implements Resolution {
        }

        record Failed(Problem problem, String reason) implements Resolution {
        }
    }

    public Resolution resolve(Optional<String> tenantName, Optional<String> unitCode) {
        if (tenantName.isEmpty() && unitCode.isEmpty()) {
            throw new ToolInputException("provide unit_code or tenant_name");
        }
        return unitCode.map(code -> byUnitCode(code, tenantName)).orElseGet(() -> byName(tenantName.get()));
    }

    private Resolution byUnitCode(String code, Optional<String> expectedName) {
        if (!UNIT_CODE.matcher(code).matches()) {
            throw new ToolInputException("unit_code must look like A-203");
        }
        var lease = leases.findActiveByUnitCode(code);
        if (lease.isEmpty()) {
            return new Resolution.Failed(Problem.NO_ACTIVE_LEASE,
                    "Unit " + code + " has no active lease (vacant, in maintenance or unknown).");
        }
        var tenant = lease.get().getTenant();
        if (expectedName.isPresent() && !matches(tenant, expectedName.get())) {
            return new Resolution.Failed(Problem.TENANT_MISMATCH, "Unit " + code + " is leased to " + tenant.getNameEn()
                    + ", which does not match '" + expectedName.get() + "'.");
        }
        return new Resolution.Found(lease.get());
    }

    private Resolution byName(String name) {
        var matches = tenants.searchByName(name);
        if (matches.isEmpty()) {
            return new Resolution.Failed(Problem.TENANT_NOT_FOUND, "No tenant matches '" + name + "'.");
        }
        var activeLeases = leases.findActiveByTenantIds(matches.stream().map(Tenant::getId).toList());
        if (activeLeases.size() == 1) {
            return new Resolution.Found(activeLeases.get(0));
        }
        if (activeLeases.isEmpty()) {
            return new Resolution.Failed(Problem.NO_ACTIVE_LEASE, "Tenant '" + name + "' has no active lease.");
        }
        return new Resolution.Failed(Problem.AMBIGUOUS_TENANT, ambiguityMessage(name, activeLeases));
    }

    private static String ambiguityMessage(String name, List<Lease> candidates) {
        var shown = candidates.stream().limit(MAX_CANDIDATES)
                .map(l -> l.getTenant().getNameEn() + " (unit " + l.getUnit().getCode() + ")")
                .toList();
        return candidates.size() + " tenants match '" + name + "'. Ask the user which one, or retry with unit_code. "
                + "First matches: " + String.join(", ", shown);
    }

    private static boolean matches(Tenant tenant, String name) {
        var needle = name.toLowerCase();
        return tenant.getNameEn().toLowerCase().contains(needle) || tenant.getNameAr().contains(name);
    }
}
