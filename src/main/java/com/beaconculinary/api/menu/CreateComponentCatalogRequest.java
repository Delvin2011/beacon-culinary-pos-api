package com.beaconculinary.api.menu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateComponentCatalogRequest {
    @NotBlank(message = "name is required")
    private String name;

    @NotNull(message = "extraPrice is required")
    @PositiveOrZero(message = "extraPrice cannot be negative")
    private BigDecimal extraPrice;
}
