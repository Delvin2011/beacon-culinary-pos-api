package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {
    @EntityGraph(attributePaths = {"lines", "lines.ingredient"})
    Optional<Recipe> findWithLinesByComponentCatalogId(Long componentCatalogId);

    /** Many components' recipes in one query: the meals list and till menu stock status. */
    @EntityGraph(attributePaths = {"lines", "lines.ingredient", "componentCatalog"})
    List<Recipe> findWithLinesByComponentCatalogIdIn(Collection<Long> componentCatalogIds);
}
