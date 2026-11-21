package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.StockStatus;

import java.math.BigDecimal;
import java.util.List;

/** GET /admin/meal-catalog/{id}/stock-review: what one unit of a light meal needs and how many
 * units its station's source location can make (T0-5c; Kitchen by default). Not tracked:
 * unitsAvailable, limitingIngredient and the source location are null and lines is empty. */
public record MealStockReviewDto(
        Long mealId,
        String name,
        boolean tracked,
        StockStatus status,
        Integer unitsAvailable,
        String limitingIngredient,
        int lowStockUnits,
        boolean outToday,
        // T0-5c: where kitchenAvailable is counted (the meal's station's source location).
        Long sourceLocationId,
        String sourceLocationName,
        List<Line> lines) {

    /** One ingredient. Stock figures are ledger sums and may be negative; unitsPossible never is.
     * Names kept for the frontend (T0-5c): kitchenAvailable is at the source location,
     * mainStoreAvailable at the primary location. */
    public record Line(
            Long ingredientId,
            String name,
            IngredientUnit uom,
            BigDecimal perUnit,
            BigDecimal kitchenAvailable,
            BigDecimal mainStoreAvailable,
            int unitsPossible,
            StockStatus status) {
    }
}
