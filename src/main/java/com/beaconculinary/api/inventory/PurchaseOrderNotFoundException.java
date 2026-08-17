package com.beaconculinary.api.inventory;

public class PurchaseOrderNotFoundException extends RuntimeException {
    public PurchaseOrderNotFoundException() {
        super("Purchase order not found.");
    }
}
