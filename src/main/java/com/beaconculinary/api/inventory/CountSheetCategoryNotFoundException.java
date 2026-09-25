package com.beaconculinary.api.inventory;

public class CountSheetCategoryNotFoundException extends RuntimeException {
    public CountSheetCategoryNotFoundException() {
        super("Count sheet category not found.");
    }
}
