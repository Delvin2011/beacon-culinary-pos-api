package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {
    @EntityGraph(attributePaths = {"lines", "lines.ingredient"})
    Optional<Recipe> findWithLinesByComponentCatalogId(Long componentCatalogId);
}
