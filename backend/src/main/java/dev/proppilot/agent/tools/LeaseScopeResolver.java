package dev.proppilot.agent.tools;

import dev.proppilot.domain.LeaseRepository;
import dev.proppilot.rag.LeaseDocumentCatalog;
import dev.proppilot.units.UnitService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns the units and the tenant a lease search names into the leases whose documents are searched.
 *
 * <p>A unit must exist, and a tenant is found the way every other tool finds one, by {@link TenantResolver}: with a
 * single unit named the tenant must live there, and an ambiguous name is refused with the candidates. A named lease
 * that has no document is not an error: the others are searched and the units that have none are reported.
 */
@Component
class LeaseScopeResolver {

    private final UnitService units;
    private final LeaseRepository leases;
    private final TenantResolver tenants;
    private final LeaseDocumentCatalog documents;

    LeaseScopeResolver(UnitService units, LeaseRepository leases, TenantResolver tenants,
                       LeaseDocumentCatalog documents) {
        this.units = units;
        this.leases = leases;
        this.tenants = tenants;
        this.documents = documents;
    }

    /**
     * @param everything nothing was named, so every document is searched
     * @param leaseIds the named leases that have a document
     * @param unitCodes the units of those leases, in the order they were named
     * @param noDocumentFor the named units whose lease has no document, or that have no active lease
     */
    record Scope(boolean everything, Set<Long> leaseIds, List<String> unitCodes, List<String> noDocumentFor) {

        static final Scope EVERYTHING = new Scope(true, Set.of(), List.of(), List.of());
    }

    sealed interface Resolution {

        record Found(Scope scope) implements Resolution {
        }

        /** @param reason what to tell the model: nothing was searched */
        record Refused(String reason) implements Resolution {
        }
    }

    /** A unit that was named, and its active lease, or null when it has none. */
    private record NamedUnit(String unitCode, Long leaseId) {
    }

    /**
     * @param unitCodes unit codes in the format of {@link TenantResolver#UNIT_CODE}, upper case, without duplicates
     */
    Resolution resolve(List<String> unitCodes, Optional<String> tenantName) {
        if (unitCodes.isEmpty() && tenantName.isEmpty()) {
            return new Resolution.Found(Scope.EVERYTHING);
        }
        var named = new LinkedHashMap<String, NamedUnit>();
        for (var code : unitCodes) {
            var unit = units.findByCode(code);
            if (unit.isEmpty()) {
                return new Resolution.Refused("No unit with code '" + code + "'. Codes look like A-203 "
                        + "(building letter A-F, then floor and number).");
            }
            var unitCode = unit.get().code();
            var leaseId = leases.findActiveByUnitCode(unitCode).map(lease -> lease.getId()).orElse(null);
            named.put(unitCode, new NamedUnit(unitCode, leaseId));
        }
        if (tenantName.isPresent()) {
            // With one unit named the tenant has to live there; the lease found is then that unit's own.
            var onlyUnit = unitCodes.size() == 1 ? Optional.of(unitCodes.get(0)) : Optional.<String>empty();
            var resolution = tenants.resolve(tenantName, onlyUnit);
            if (resolution instanceof TenantResolver.Resolution.Failed failed) {
                return new Resolution.Refused(failed.reason());
            }
            var lease = ((TenantResolver.Resolution.Found) resolution).lease();
            var unitCode = lease.getUnit().getCode();
            named.putIfAbsent(unitCode, new NamedUnit(unitCode, lease.getId()));
        }
        return new Resolution.Found(scopeOf(named.values()));
    }

    private Scope scopeOf(Iterable<NamedUnit> named) {
        var leaseIds = new ArrayList<Long>();
        for (var unit : named) {
            if (unit.leaseId() != null) {
                leaseIds.add(unit.leaseId());
            }
        }
        var withDocument = documents.leasesWithDocument(leaseIds);
        var searched = new LinkedHashSet<Long>();
        var searchedUnits = new ArrayList<String>();
        var without = new ArrayList<String>();
        for (var unit : named) {
            if (unit.leaseId() != null && withDocument.contains(unit.leaseId())) {
                searched.add(unit.leaseId());
                searchedUnits.add(unit.unitCode());
            } else {
                without.add(unit.unitCode());
            }
        }
        return new Scope(false, searched, searchedUnits, without);
    }
}
