package dev.proppilot.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaseRepository extends JpaRepository<Lease, Long> {

    @EntityGraph(attributePaths = {"unit", "unit.building", "tenant"})
    @Query("select l from Lease l where l.active = true and upper(l.unit.code) = upper(:unitCode)")
    Optional<Lease> findActiveByUnitCode(@Param("unitCode") String unitCode);

    @EntityGraph(attributePaths = {"unit", "unit.building", "tenant"})
    @Query("select l from Lease l where l.active = true and l.tenant.id = :tenantId")
    List<Lease> findActiveByTenantId(@Param("tenantId") Long tenantId);
}
