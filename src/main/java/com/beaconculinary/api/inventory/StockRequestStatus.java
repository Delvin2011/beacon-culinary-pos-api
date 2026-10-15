package com.beaconculinary.api.inventory;

public enum StockRequestStatus {
    REQUESTED,
    PARTIALLY_ACTIONED,
    ACTIONED,
    REJECTED,
    // Withdrawn by whoever submitted it while still REQUESTED; nothing was moved.
    CANCELLED
}
