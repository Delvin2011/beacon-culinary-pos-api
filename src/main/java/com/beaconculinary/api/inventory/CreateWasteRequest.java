package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateWasteRequest {
    @NotNull(message = "ingredientId is required")
    private Long ingredientId;

    @NotNull(message = "quantity is required")
    @DecimalMin(value = "0.0001", message = "quantity must be positive")
    private BigDecimal quantity;

    @NotBlank(message = "reason is required")
    private String reason;

    private String note;
}
