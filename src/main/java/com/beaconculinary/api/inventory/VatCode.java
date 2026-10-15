package com.beaconculinary.api.inventory;

/** GRV VAT handling: how VAT applies to a purchased item. STANDARD uses the configured standard
 * rate (15%); ZERO_RATED (e.g. rice, eggs, milk, fresh produce) and NO_VAT carry 0%. */
public enum VatCode {
    STANDARD,
    ZERO_RATED,
    NO_VAT
}
