package com.beaconculinary.api.auth.protection;

import java.time.Duration;

/**
 * Thrown in enforce mode, before any credential check, when a lock or block refuses the attempt.
 * Turned into HTTP 429 by {@link LoginBlockedExceptionHandler}. Deliberately not an
 * AuthenticationException, so no existing 401 handler catches it.
 */
public class LoginBlockedException extends RuntimeException {
    private final LoginRule rule;
    private final transient Duration retryAfter;

    public LoginBlockedException(LoginRule rule, Duration retryAfter) {
        super("Login refused by " + rule);
        this.rule = rule;
        this.retryAfter = retryAfter;
    }

    public LoginRule getRule() {
        return rule;
    }

    /** Null when the lock only ends with a manager unlock. */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    /** ACCOUNT_LOCKED for account locks (LOCK rules), RATE_LIMITED for window blocks. */
    public LoginProtectionErrorDto body() {
        return rule.effect() == LoginRule.Effect.LOCK
                ? LoginProtectionErrorDto.accountLocked(retryAfter)
                : LoginProtectionErrorDto.rateLimited(retryAfter);
    }
}
