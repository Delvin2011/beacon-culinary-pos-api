package com.beaconculinary.api.shifts;

/**
 * O2: whether a closed shift's drawer was counted (shifts.cash_count). COUNTED for every normal
 * close, and while the shift is open. A force close without a count leaves it UNCOUNTED until an
 * admin records a late count (COUNTED_LATE) or confirms at sign-off that there will be none
 * (NO_COUNT_CONFIRMED). Counted cash and variance are empty for UNCOUNTED and NO_COUNT_CONFIRMED.
 */
public enum ShiftCashCount {
    COUNTED,
    UNCOUNTED,
    COUNTED_LATE,
    NO_COUNT_CONFIRMED;

    /** Counted cash and variance are recorded. */
    public boolean counted() {
        return this == COUNTED || this == COUNTED_LATE;
    }
}
