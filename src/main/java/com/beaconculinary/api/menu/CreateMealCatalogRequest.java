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
public class CreateMealCatalogRequest {
    @NotBlank(message = "name is required")
    private String name;

    private String description;

    @NotNull(message = "price is required")
    @PositiveOrZero(message = "price cannot be negative")
    private BigDecimal price;

    // A leaf of the catalogue tree: "Set meals" or a subcategory.
    @NotNull(message = "categoryId is required")
    private Long categoryId;

    // Beverages only: BAR or NONE. Optional; defaults from the category (Cold beverages: NONE,
    // otherwise BAR). Set meals and light meals are always KITCHEN.
    private Station station;

    // Light meal stock: warn LOW when the Kitchen can make fewer units than this. Optional; 10 on
    // create, unchanged on update when left out.
    @Min(value = 0, message = "lowStockUnits must be between 0 and 10000")
    @Max(value = 10000, message = "lowStockUnits must be between 0 and 10000")
    private Integer lowStockUnits;

    private List<Long> componentIds = List.of();
}
