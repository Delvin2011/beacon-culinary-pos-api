package com.beaconculinary.api.menu;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MealCatalogRepository extends JpaRepository<MealCatalog, Long> {
    @EntityGraph(attributePaths = {"components", "components.componentCatalog"})
    List<MealCatalog> findAllWithComponentsBy();

    @EntityGraph(attributePaths = {"components", "components.componentCatalog"})
    Optional<MealCatalog> findWithComponentsById(Long id);
}
