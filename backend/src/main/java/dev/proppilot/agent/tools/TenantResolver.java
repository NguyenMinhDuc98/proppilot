package dev.proppilot.agent.tools;

import dev.proppilot.domain.Lease;
import dev.proppilot.domain.LeaseRepository;
import dev.proppilot.domain.Tenant;
import dev.proppilot.domain.TenantRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Finds the single active lease a tool call is talking about, by unit code and/or tenant name, and lists the tenants a
 * name stands for. The two share one search, so that the candidates {@code find_tenant} returns are exactly the ones
 * that make the other tools refuse a name as ambiguous.
 */
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

            /**
             * The error a tool returns. Only the reasons that name tenants state data: the others repeat the unit
             * or the name the model sent.
             */
            public ToolResult toolResult() {
                return switch (problem) {
                    case NO_ACTIVE_LEASE, TENANT_NOT_FOUND -> ToolResult.rejected(reason);
                    case TENANT_MISMATCH, AMBIGUOUS_TENANT -> ToolResult.error(reason);
                };
            }
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

    /**
     * A tenant whose name matches, and one of the active leases they hold.
     *
     * @param activeLease null for a tenant that has none; a tenant with several is listed once for each
     */
    public record Candidate(Tenant tenant, Lease activeLease) {
    }

    /**
     * Every tenant whose English or Arabic name contains {@code name}, in the order of the tenants, each with its
     * active leases. An empty list when no tenant matches.
     */
    public List<Candidate> candidates(String name) {
        var matches = tenants.searchByName(name);
        if (matches.isEmpty()) {
            return List.of();
        }
        var leasesByTenant = leases.findActiveByTenantIds(matches.stream().map(Tenant::getId).toList()).stream()
                .collect(Collectors.groupingBy(lease -> lease.getTenant().getId()));
        var candidates = new ArrayList<Candidate>();
        for (var tenant : matches) {
            var held = leasesByTenant.getOrDefault(tenant.getId(), List.of());
            if (held.isEmpty()) {
                candidates.add(new Candidate(tenant, null));
            }
            held.forEach(lease -> candidates.add(new Candidate(tenant, lease)));
        }
        return List.copyOf(candidates);
    }

    private Resolution byName(String name) {
        var candidates = candidates(name);
        if (candidates.isEmpty()) {
            return new Resolution.Failed(Problem.TENANT_NOT_FOUND, "No tenant matches '" + name + "'.");
        }
        var activeLeases = candidates.stream().map(Candidate::activeLease).filter(Objects::nonNull).toList();
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
