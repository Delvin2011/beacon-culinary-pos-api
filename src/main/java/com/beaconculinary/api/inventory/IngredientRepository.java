package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IngredientRepository extends JpaRepository<Ingredient, Long> {
    List<Ingredient> findAllByIdIn(List<Long> ids);

    Optional<Ingredient> findByNameIgnoreCase(String name);
}
