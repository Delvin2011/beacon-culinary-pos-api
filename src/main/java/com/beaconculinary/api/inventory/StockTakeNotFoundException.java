package com.beaconculinary.api.inventory;

public class StockTakeNotFoundException extends RuntimeException {
    public StockTakeNotFoundException() {
        super("Stock take not found.");
    }
}
