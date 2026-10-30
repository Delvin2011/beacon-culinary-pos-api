package com.beaconculinary.api.menu;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MealCatalogRepository extends JpaRepository<MealCatalog, Long> {
    @EntityGraph(attributePaths = {"components", "components.componentCatalog", "category"})
    List<MealCatalog> findAllWithComponentsBy();

    @EntityGraph(attributePaths = {"components", "components.componentCatalog", "category"})
    Optional<MealCatalog> findWithComponentsById(Long id);

    /** Active meals that are always available (every group but set meals): the always-on menu. */
    @EntityGraph(attributePaths = {"category"})
    @Query("SELECT m FROM MealCatalog m WHERE m.active = true AND m.category.group <> com.beaconculinary.api.menu.CatalogGroup.SET_MEALS")
    List<MealCatalog> findActiveAlwaysOn();
}
