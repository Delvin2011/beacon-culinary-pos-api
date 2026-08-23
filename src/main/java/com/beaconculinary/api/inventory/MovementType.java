package com.beaconculinary.api.inventory;

public enum MovementType {
    RECEIVED,
    // Not yet produced anywhere — reserved for Stage 5.2.1 Section 3's Main Store -> Kitchen
    // issue-approval movement pair, a later follow-up.
    ISSUED,
    CONSUMED,
    WASTED,
    STOCK_TAKE_ADJUSTMENT
}
