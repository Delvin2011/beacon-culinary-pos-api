package com.beaconculinary.api.orders;

/** Why a manager approved a discount at sale. OTHER requires a note. */
public enum SaleDiscountReasonCode {
    STAFF,
    PROMOTION,
    MANAGER_GOODWILL,
    PRICING_CORRECTION,
    OTHER
}
