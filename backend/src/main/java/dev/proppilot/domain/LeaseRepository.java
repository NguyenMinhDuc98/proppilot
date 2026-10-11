package dev.proppilot.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaseRepository extends JpaRepository<Lease, Long> {

    @EntityGraph(attributePaths = {"unit", "unit.building", "tenant"})
    @Query("select l from Lease l where l.active = true and upper(l.unit.code) = upper(:unitCode)")
    Optional<Lease> findActiveByUnitCode(@Param("unitCode") String unitCode);

    @EntityGraph(attributePaths = {"unit", "unit.building", "tenant"})
    @Query("select l from Lease l where l.active = true and l.tenant.id in :tenantIds order by l.tenant.id, l.id")
    List<Lease> findActiveByTenantIds(@Param("tenantIds") Collection<Long> tenantIds);

    /** The active leases that end from {@code from} to {@code to}, both days included, soonest first. */
    @EntityGraph(attributePaths = {"unit", "tenant"})
    @Query(value = """
            select l from Lease l
            where l.active = true and l.endDate between :from and :to
            order by l.endDate, l.unit.code""",
            countQuery = "select count(l) from Lease l where l.active = true and l.endDate between :from and :to")
    Page<Lease> findActiveEndingBetween(@Param("from") LocalDate from, @Param("to") LocalDate to, Pageable page);
}
