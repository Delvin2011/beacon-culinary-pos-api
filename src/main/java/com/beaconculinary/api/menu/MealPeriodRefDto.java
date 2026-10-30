package com.beaconculinary.api.menu;

/** A menu item's meal period; null for beverages, which have none. */
public record MealPeriodRefDto(Long id, String name) {
    static MealPeriodRefDto of(MealPeriod period) {
        return period == null ? null : new MealPeriodRefDto(period.getId(), period.getName());
    }
}
