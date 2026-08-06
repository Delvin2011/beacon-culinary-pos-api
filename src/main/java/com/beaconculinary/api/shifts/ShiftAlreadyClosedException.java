package com.beaconculinary.api.shifts;

public class ShiftAlreadyClosedException extends RuntimeException {
    public ShiftAlreadyClosedException() {
        super("Shift is already closed.");
    }
}
