package com.beaconculinary.api.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IngredientStockMovementRepository extends JpaRepository<IngredientStockMovement, Long> {
    /** Total across every location — every ingredient's grand-total stock figure derives from
     * this one query — never a stored/mutable field on {@link Ingredient}. */
    @Query("SELECT COALESCE(SUM(m.quantity), 0) FROM IngredientStockMovement m WHERE m.ingredient.id = :ingredientId")
    BigDecimal sumQuantityByIngredientId(@Param("ingredientId") Long ingredientId);

    /** Stage 5.2.1 — the per-location figure everything except the total-stock display now
     * actually cares about (e.g. "does Kitchen have enough"). */
    @Query("SELECT COALESCE(SUM(m.quantity), 0) FROM IngredientStockMovement m " +
            "WHERE m.ingredient.id = :ingredientId AND m.location.id = :locationId")
    BigDecimal sumQuantityByIngredientIdAndLocationId(
            @Param("ingredientId") Long ingredientId, @Param("locationId") Long locationId);

    @Query("SELECT m.location.id, COALESCE(SUM(m.quantity), 0) FROM IngredientStockMovement m " +
            "WHERE m.ingredient.id = :ingredientId GROUP BY m.location.id")
    List<Object[]> sumQuantityByIngredientIdGroupedByLocation(@Param("ingredientId") Long ingredientId);

    @Query("SELECT MAX(m.createdAt) FROM IngredientStockMovement m WHERE m.ingredient.id = :ingredientId")
    LocalDateTime findLastMovementAtByIngredientId(@Param("ingredientId") Long ingredientId);

    // Stage 5.2.5 — "current cost" for a stock take line, same source as everywhere else in this
    // system (the most recent GRV's cost). Excludes RECEIVED rows with no cost, since the
    // Kitchen-side of an approved Issue is also RECEIVED but carries no cost of its own — only a
    // purchase actually sets one.
    Optional<IngredientStockMovement> findFirstByIngredientIdAndMovementTypeAndCostPerUnitIsNotNullOrderByCreatedAtDesc(
            Long ingredientId, MovementType movementType);
}
