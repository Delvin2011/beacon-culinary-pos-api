package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateStockTakeRequest {
    @NotNull(message = "ingredientId is required")
    private Long ingredientId;

    @NotNull(message = "countedQuantity is required")
    @PositiveOrZero(message = "countedQuantity cannot be negative")
    private BigDecimal countedQuantity;

    private String note;
}
