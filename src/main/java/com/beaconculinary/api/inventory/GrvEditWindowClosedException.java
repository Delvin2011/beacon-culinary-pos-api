package com.beaconculinary.api.inventory;

public class GrvEditWindowClosedException extends RuntimeException {
    public GrvEditWindowClosedException() {
        super("This GRV can no longer be edited — it was received on a previous day.");
    }
}
