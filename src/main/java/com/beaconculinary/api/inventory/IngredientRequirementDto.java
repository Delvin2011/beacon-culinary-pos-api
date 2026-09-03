package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class IngredientRequirementDto {
    private Long ingredientId;
    private String name;
    private IngredientUnit unit;
    private BigDecimal calculatedQuantity;
    // Main Store's current stock — the issue-source figure the eventual StockRequest approval
    // caps against (see DailyPlanningIngredientService#confirm).
    private BigDecimal currentStock;
    // Kitchen's current stock — the issue-destination figure, for the frontend to compare
    // against calculatedQuantity and flag "Kitchen is low, an Issue will be generated" on its
    // own, independent of whether Main Store can actually supply it.
    private BigDecimal kitchenStock;
}
