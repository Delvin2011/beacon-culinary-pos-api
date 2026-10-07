package com.beaconculinary.api.oversight;

/** POS Oversight A4: where an expected-cash breakdown's parts come from. */
public enum BreakdownSource {
    /** Open shift: computed now by ShiftCashCalculator. */
    LIVE,
    /** Closed after V82: the parts stored at close, cross-checked against a recomputation. */
    SNAPSHOT,
    /** Closed before V82: parts recomputed now; never flagged, since older closes used an earlier formula. */
    RECALCULATED
}
