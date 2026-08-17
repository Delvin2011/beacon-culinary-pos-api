package com.beaconculinary.api.inventory;

public class IngredientNotFoundException extends RuntimeException {
    public IngredientNotFoundException() {
        super("Ingredient not found.");
    }
}
