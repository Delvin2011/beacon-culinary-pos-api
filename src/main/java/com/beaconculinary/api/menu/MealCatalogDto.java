package com.beaconculinary.api.menu;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MealCatalogDto {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private boolean active;
    private CatalogCategoryRefDto category;
    private CatalogGroup group;
    private Availability availability;
    private Station station;
    // ALWAYS items only: marked out for today (Johannesburg); clears itself the next day.
    private boolean outToday;
    private int lowStockUnits;
    // Always-available rows only (null for set meals): light meal stock at the Kitchen. Beverages
    // are NOT_TRACKED. unitsAvailable is null unless tracked.
    private StockStatus stockStatus;
    private Integer unitsAvailable;
    private List<ComponentCatalogDto> components;
}
