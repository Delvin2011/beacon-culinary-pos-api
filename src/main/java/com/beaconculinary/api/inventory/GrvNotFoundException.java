package com.beaconculinary.api.inventory;

public class GrvNotFoundException extends RuntimeException {
    public GrvNotFoundException() {
        super("GRV not found.");
    }
}
