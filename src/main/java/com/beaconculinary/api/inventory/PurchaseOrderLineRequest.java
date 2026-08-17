package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PurchaseOrderLineRequest {
    @NotNull(message = "ingredientId is required")
    private Long ingredientId;

    @NotNull(message = "quantity is required")
    @DecimalMin(value = "0.0001", message = "quantity must be positive")
    private BigDecimal quantity;

    private String note;
}
