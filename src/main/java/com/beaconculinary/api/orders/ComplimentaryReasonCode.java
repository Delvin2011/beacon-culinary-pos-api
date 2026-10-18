package com.beaconculinary.api.orders;

/** Why a manager approved a complimentary meal. OTHER requires a note. */
public enum ComplimentaryReasonCode {
    STAFF_MEAL,
    MANAGEMENT,
    GUEST,
    TASTING,
    OTHER
}
