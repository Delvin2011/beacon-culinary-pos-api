package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateStockTakeLineRequest {
    @NotNull(message = "ingredientId is required")
    private Long ingredientId;

    @NotNull(message = "actualQuantity is required")
    @PositiveOrZero(message = "actualQuantity cannot be negative")
    private BigDecimal actualQuantity;
}
