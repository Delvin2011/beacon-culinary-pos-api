package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class IngredientRequirementAdjustmentRequest {
    @NotNull(message = "ingredientId is required")
    private Long ingredientId;

    @NotNull(message = "finalQuantity is required")
    @PositiveOrZero(message = "finalQuantity cannot be negative")
    private BigDecimal finalQuantity;
}
