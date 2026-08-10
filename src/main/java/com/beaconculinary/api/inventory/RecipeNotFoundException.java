package com.beaconculinary.api.inventory;

public class RecipeNotFoundException extends RuntimeException {
    public RecipeNotFoundException() {
        super("This component has no recipe configured yet.");
    }
}
