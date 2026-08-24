package com.beaconculinary.api.inventory;

/** A stock request already {@code ACTIONED} or {@code REJECTED} can't be actioned again. */
public class StockRequestAlreadyFinalizedException extends RuntimeException {
    public StockRequestAlreadyFinalizedException() {
        super("Stock request has already been actioned or rejected.");
    }
}
