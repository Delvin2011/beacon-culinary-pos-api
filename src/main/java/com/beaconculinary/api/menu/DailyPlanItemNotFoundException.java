package com.beaconculinary.api.menu;

public class DailyPlanItemNotFoundException extends RuntimeException {
    public DailyPlanItemNotFoundException() {
        super("Daily plan item not found.");
    }
}
