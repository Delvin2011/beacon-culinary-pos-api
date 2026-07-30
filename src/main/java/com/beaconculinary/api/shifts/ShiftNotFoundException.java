package com.beaconculinary.api.shifts;

public class ShiftNotFoundException extends RuntimeException {
    public ShiftNotFoundException() {
        super("Shift not found.");
    }
}
