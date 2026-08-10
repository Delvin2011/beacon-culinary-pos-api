package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface IngredientStockMovementRepository extends JpaRepository<IngredientStockMovement, Long> {
    /** Every ingredient stock figure in this system derives from this one query — never a
     * stored/mutable field on {@link Ingredient}. */
    @Query("SELECT COALESCE(SUM(m.quantity), 0) FROM IngredientStockMovement m WHERE m.ingredient.id = :ingredientId")
    BigDecimal sumQuantityByIngredientId(@Param("ingredientId") Long ingredientId);

    @Query("SELECT MAX(m.createdAt) FROM IngredientStockMovement m WHERE m.ingredient.id = :ingredientId")
    LocalDateTime findLastMovementAtByIngredientId(@Param("ingredientId") Long ingredientId);
}
