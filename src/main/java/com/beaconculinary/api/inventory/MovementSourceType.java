package com.beaconculinary.api.inventory;

public enum MovementSourceType {
    GRV,
    DAILY_PLANNING_CONFIRMATION,
    // Renamed from WASTE_ENTRY (Stage 5 Revision) — distinguishes a STOCK_ADMIN's own
    // self-logged write-off from one that went through the STOCK_CLERK request-then-authorize
    // path (STOCK_REQUEST below). Both write the same WASTED movement shape.
    DIRECT_WASTE,
    STOCK_TAKE,
    // Stage 5 Revision — an approved StockRequest (ISSUE or WASTE) authored this movement.
    STOCK_REQUEST
}
