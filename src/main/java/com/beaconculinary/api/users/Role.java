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
    STOCK_ADMIN,
    // Head Chef — plans daily menus (create/edit/confirm), views the catalog and ingredient stock,
    // requests stock issues and waste, submits stock takes, uses the kitchen display, and reviews
    // food sales (Sales Summary). Never approves, never holds manager-PIN authority, and never
    // raises purchase-order requests.
    HEAD_CHEF
}
