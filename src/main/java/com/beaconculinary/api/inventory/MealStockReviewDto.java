package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.StockStatus;

import java.math.BigDecimal;
import java.util.List;

/** GET /admin/meal-catalog/{id}/stock-review: what one unit of a light meal needs and how many
 * units the Kitchen can make. Not tracked: unitsAvailable and limitingIngredient are null and
 * lines is empty. */
public record MealStockReviewDto(
        Long mealId,
        String name,
        boolean tracked,
        StockStatus status,
        Integer unitsAvailable,
        String limitingIngredient,
        int lowStockUnits,
        boolean outToday,
        List<Line> lines) {

    /** One ingredient. Stock figures are ledger sums and may be negative; unitsPossible never is. */
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
