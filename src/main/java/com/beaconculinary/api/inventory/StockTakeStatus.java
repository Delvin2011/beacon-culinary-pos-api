package com.beaconculinary.api.inventory;

public enum StockTakeStatus {
    SUBMITTED,
    APPROVED,
    REJECTED,
    // Withdrawn by whoever submitted it while still SUBMITTED; no adjustment was applied.
    CANCELLED
}
