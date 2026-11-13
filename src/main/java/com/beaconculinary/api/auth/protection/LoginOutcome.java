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
    VALIDATION;

    /** Whether this outcome counts towards the failure thresholds. */
    public boolean isFailure() {
        return this == BAD_CREDENTIAL || this == UNKNOWN_USER || this == DISABLED;
    }
}
