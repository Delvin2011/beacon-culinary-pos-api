package com.beaconculinary.api.menu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class UpdateMealCatalogRequest {
    @NotBlank(message = "name is required")
    private String name;

    private String description;

    @NotNull(message = "price is required")
    @PositiveOrZero(message = "price cannot be negative")
    private BigDecimal price;

    private boolean active = true;

    private List<Long> componentIds = List.of();
}
