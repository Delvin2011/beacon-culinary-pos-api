package com.beaconculinary.api.orders;

public class ExtrasAlreadyAdjustedException extends RuntimeException {
    public ExtrasAlreadyAdjustedException() {
        super("Extras have already been adjusted for this order.");
    }
}
