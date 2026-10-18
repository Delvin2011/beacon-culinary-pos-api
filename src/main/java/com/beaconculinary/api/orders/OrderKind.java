package com.beaconculinary.api.orders;

/** SALE: a paid order. COMPLIMENTARY: a manager-approved order with no payment (staff meals,
 * management, guests, tastings); its list value is kept in preDiscountTotal and every money
 * figure that was paid is 0. Kitchen, stock and order numbers treat both the same. */
public enum OrderKind {
    SALE,
    COMPLIMENTARY
}
