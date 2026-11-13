package com.beaconculinary.api.auth.protection;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;

/**
 * The 429 body for a login-protection refusal (decision D2). Every field is always present;
 * retryAfterSeconds is null only when a manager has to unlock sign-in. Messages are generic and
 * never say whether an account exists. See docs/2026-11-11-login-protection-api-contract.md.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LoginProtectionErrorDto(String code, String message, Long retryAfterSeconds) {
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String RATE_LIMITED = "RATE_LIMITED";

    static final String LOCKED_MESSAGE = "Too many failed attempts. Try again later.";
    static final String ADMIN_UNLOCK_MESSAGE = "Too many failed attempts. Ask a manager to unlock sign-in.";
    static final String RATE_LIMITED_MESSAGE = "Too many attempts. Try again later.";

    /** An account lock; {@code retryAfter} null means a manager must unlock it. */
    public static LoginProtectionErrorDto accountLocked(Duration retryAfter) {
        return retryAfter == null
                ? new LoginProtectionErrorDto(ACCOUNT_LOCKED, ADMIN_UNLOCK_MESSAGE, null)
                : new LoginProtectionErrorDto(ACCOUNT_LOCKED, LOCKED_MESSAGE, seconds(retryAfter));
    }

    public static LoginProtectionErrorDto rateLimited(Duration retryAfter) {
        return new LoginProtectionErrorDto(RATE_LIMITED, RATE_LIMITED_MESSAGE, seconds(retryAfter));
    }

    /** Whole seconds, rounded up, at least 1: the value of the Retry-After header. */
    public static long seconds(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return 1;
        }
        var seconds = duration.getSeconds() + (duration.getNano() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
