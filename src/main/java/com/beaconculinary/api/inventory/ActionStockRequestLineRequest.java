package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ActionStockRequestLineRequest {
    @NotNull(message = "ingredientId is required")
    private Long ingredientId;

    // Zero is a valid, deliberate "reject this line" — never negative.
    @NotNull(message = "actionedQuantity is required")
    @DecimalMin(value = "0.00", message = "actionedQuantity cannot be negative")
    private BigDecimal actionedQuantity;
}
