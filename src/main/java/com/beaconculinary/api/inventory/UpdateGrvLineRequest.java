package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateGrvLineRequest {
    // Identifies which existing GrvLine this edit applies to — a line can never be added,
    // removed, or repointed to a different ingredient/purchase-order-line via this endpoint.
    @NotNull(message = "id is required")
    private Long id;

    @NotNull(message = "quantityReceived is required")
    @DecimalMin(value = "0.0001", message = "quantityReceived must be positive")
    private BigDecimal quantityReceived;

    @NotNull(message = "costPerUnit is required")
    @DecimalMin(value = "0.00", message = "costPerUnit cannot be negative")
    private BigDecimal costPerUnit;
}
