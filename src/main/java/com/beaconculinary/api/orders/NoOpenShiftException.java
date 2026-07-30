package com.beaconculinary.api.orders;

public class NoOpenShiftException extends RuntimeException {
    public NoOpenShiftException() {
        super("An open shift is required to place an order.");
    }
}
