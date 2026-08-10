package com.beaconculinary.api.orders;

/** Stage 4 Part C — how an adjustment's money is actually paid out, computed automatically from
 * whether the order's payment included ACCOUNT. Never client-supplied. */
public enum RefundMethod {
    CASH,
    ACCOUNT_BALANCE
}
