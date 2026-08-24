package com.beaconculinary.api.users;

public enum Role {
    USER,
    CASHIER,
    ADMIN,
    KITCHEN,
    // Stage 5 Revision — request-only: can submit Stock Requests (Issue/Waste) but has no
    // authority to action them, and no access to GRV/Stock Take/Purchase Orders.
    STOCK_CLERK,
    // Stage 5 Revision — authorizes/performs stock actions (GRV, Stock Take, Purchase Orders,
    // direct Waste, actioning Stock Requests). ADMIN is a superset of every STOCK_ADMIN right.
    STOCK_ADMIN
}
