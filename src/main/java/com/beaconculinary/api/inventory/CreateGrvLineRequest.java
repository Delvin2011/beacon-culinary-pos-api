package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateGrvLineRequest {
    @NotNull(message = "ingredientId is required")
    private Long ingredientId;

    // Optional — when present, quantityOrdered is derived from this line and must reference the
    // same ingredient as ingredientId above. Omitted means an ad-hoc receipt.
    private Long purchaseOrderLineId;

    @NotNull(message = "quantityReceived is required")
    @DecimalMin(value = "0.0001", message = "quantityReceived must be positive")
    private BigDecimal quantityReceived;

    @NotNull(message = "costPerUnit is required")
    @DecimalMin(value = "0.00", message = "costPerUnit cannot be negative")
    private BigDecimal costPerUnit;
}
