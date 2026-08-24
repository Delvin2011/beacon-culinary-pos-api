package com.beaconculinary.api.inventory;

public class StockRequestNotFoundException extends RuntimeException {
    public StockRequestNotFoundException() {
        super("Stock request not found.");
    }
}
