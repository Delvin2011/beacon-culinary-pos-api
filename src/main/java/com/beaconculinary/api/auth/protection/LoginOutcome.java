package com.beaconculinary.api.auth.protection;

/** The result of one attempt, stored as login_attempts.outcome. */
public enum LoginOutcome {
    SUCCESS,
    /** The user exists but the credential was wrong. */
    BAD_CREDENTIAL,
    /** No such user, or the user has no credential of this kind (for example a PIN-only user at password login). */
    UNKNOWN_USER,
    /** The user is deactivated. */
    DISABLED,
    /** Refused because the account was locked. Enforce mode only (Phase 2). */
    LOCKED,
    /** Refused by a rate limit. Enforce mode only (Phase 2). */
    RATE_LIMITED,
    /** The request failed validation before any credential check. */
    VALIDATION,
    /** WS1-1b: refused because a registered POS till is required (no credential check). */
    DEVICE_NOT_REGISTERED,
    /** O2: correct credential, refused because the user has an open shift on another till. Not a failure. */
    SHIFT_OPEN_ON_OTHER_TILL,
    /** O2: correct credential, refused because this till's shift belongs to someone else. Not a failure. */
    TILL_LOCKED,
    /** T0-6a: a demo account refused by the demo guard (any credential, right or wrong). Not a failure. */
    DEMO_ACCOUNT;

    /** Whether this outcome counts towards the failure thresholds. */
    public boolean isFailure() {
        return this == BAD_CREDENTIAL || this == UNKNOWN_USER || this == DISABLED;
    }
}
