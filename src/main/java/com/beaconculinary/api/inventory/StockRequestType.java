package com.beaconculinary.api.inventory;

public enum StockRequestType {
    ISSUE,
    WASTE,
    // Stage 5.2.3 — approving an ORDER request doesn't move stock; it produces a PurchaseOrder.
    ORDER
}
