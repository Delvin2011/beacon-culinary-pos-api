package com.beaconculinary.api.shifts;

public class ShiftAlreadyOpenException extends RuntimeException {
    public ShiftAlreadyOpenException() {
        super("A shift is already open for this cashier.");
    }
}
