package com.beaconculinary.api.menu;

public class MealCatalogNotFoundException extends RuntimeException {
    public MealCatalogNotFoundException() {
        super("Meal catalog entry not found.");
    }
}
