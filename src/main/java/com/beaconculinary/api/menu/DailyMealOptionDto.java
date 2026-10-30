package com.beaconculinary.api.menu;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class DailyMealOptionDto {
    private Long id;
    private Long mealCatalogId;
    // null for beverages, which have no period.
    private Long mealPeriodId;
    private MealPeriodRefDto period;
    private LocalDate optionDate;
    private String name;
    private String description;
    private BigDecimal price;
    private CatalogGroup group;
    private CatalogCategoryRefDto category;
    private DailyPlanItemStatus status;
    // All three are null for an always-on item (light meals, beverages): unlimited.
    private Integer plannedPortions;
    private Integer actualPortions;
    private Integer portionsRemaining;
    // The meal is marked out on this option's day: shown, but can't be sold.
    private boolean outToday;
    // Till menu, always-on options only (null for planned options): light meal stock at the
    // Kitchen, a warning chip only; selling is never blocked. unitsAvailable is null unless tracked.
    private StockStatus stockStatus;
    private Integer unitsAvailable;
}
