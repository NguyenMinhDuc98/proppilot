package dev.proppilot.units;

import dev.proppilot.domain.Unit;
import java.util.ArrayList;
import org.springframework.data.jpa.domain.Specification;

final class UnitSpecifications {

    private UnitSpecifications() {
    }

    static Specification<Unit> from(UnitFilter filter) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            var building = root.join("building");
            var city = building.join("city");
            if (filter.city() != null && !filter.city().isBlank()) {
                predicates.add(cb.or(
                        cb.equal(cb.lower(city.get("nameEn")), filter.city().strip().toLowerCase()),
                        cb.equal(city.get("nameAr"), filter.city().strip())));
            }
            if (filter.building() != null && !filter.building().isBlank()) {
                predicates.add(cb.equal(cb.upper(building.get("code")), filter.building().strip().toUpperCase()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.bedrooms() != null) {
                predicates.add(cb.equal(root.get("bedrooms"), filter.bedrooms()));
            }
            if (filter.minRent() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("monthlyRent"), filter.minRent()));
            }
            if (filter.maxRent() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("monthlyRent"), filter.maxRent()));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
