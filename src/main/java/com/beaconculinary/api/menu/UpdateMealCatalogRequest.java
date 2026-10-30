package com.beaconculinary.api.menu;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    // A leaf of the catalogue tree. Moving to another group is refused while the meal has planned
    // options today or later (409 MEAL_HAS_PLANNED_OPTIONS).
    @NotNull(message = "categoryId is required")
    private Long categoryId;

    // Beverages only: BAR or NONE. Optional; keeps the current station when it still fits, else the
    // category's default (see CreateMealCatalogRequest).
    private Station station;

    private boolean active = true;

    // Light meal stock: warn LOW when the Kitchen can make fewer units than this. Optional; 10 on
    // create, unchanged on update when left out.
    @Min(value = 0, message = "lowStockUnits must be between 0 and 10000")
    @Max(value = 10000, message = "lowStockUnits must be between 0 and 10000")
    private Integer lowStockUnits;

    private List<Long> componentIds = List.of();
}
