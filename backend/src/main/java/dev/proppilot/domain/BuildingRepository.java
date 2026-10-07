package dev.proppilot.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildingRepository extends JpaRepository<Building, Long> {

    @EntityGraph(attributePaths = "city")
    List<Building> findAllByOrderByCode();

    @EntityGraph(attributePaths = "city")
    Optional<Building> findByCodeIgnoreCase(String code);
}
