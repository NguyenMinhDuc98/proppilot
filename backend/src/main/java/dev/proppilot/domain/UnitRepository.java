package dev.proppilot.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.query.Param;

public interface UnitRepository extends JpaRepository<Unit, Long>, JpaSpecificationExecutor<Unit> {

    @Override
    @EntityGraph(attributePaths = {"building", "building.city"})
    Page<Unit> findAll(Specification<Unit> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"building", "building.city"})
    Optional<Unit> findByCodeIgnoreCase(String code);

    @Query("""
            select u.status as status, count(u) as total, coalesce(sum(u.monthlyRent), 0) as rentRoll
            from Unit u
            where (:building is null or u.building.code = :building)
            group by u.status""")
    List<StatusCount> countByStatus(@Param("building") String building);

    interface StatusCount {
        UnitStatus getStatus();

        long getTotal();

        java.math.BigDecimal getRentRoll();
    }
}
