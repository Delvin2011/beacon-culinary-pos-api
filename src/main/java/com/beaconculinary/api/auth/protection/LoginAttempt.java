package com.beaconculinary.api.auth.protection;

/**
 * One credential check in progress: created by {@link LoginAttemptService#begin} before the check
 * and passed to {@link LoginAttemptService#finish} afterwards. An inactive attempt (mode off, or
 * the check itself failed) records nothing.
 *
 * @param lockRule     enforce mode: the LOCK rule this method can trigger (PASSWORD_ACCOUNT,
 *                     PIN_ACCOUNT_CEILING), or null
 * @param lockFailures enforce mode: failures already counted towards {@code lockRule}
 */
public record LoginAttempt(boolean active, LoginMethod method, String identifier, LoginRequestInfo request,
                           LoginDecision decision, LoginRule lockRule, long lockFailures) {

    public LoginAttempt(boolean active, LoginMethod method, String identifier, LoginRequestInfo request,
                        LoginDecision decision) {
        this(active, method, identifier, request, decision, null, 0);
    }

    static LoginAttempt inactive(LoginMethod method) {
        return new LoginAttempt(false, method, null, LoginRequestInfo.NONE, LoginDecision.ALLOW);
    }

    /** Whether a rule would block this attempt. In monitor mode this never changes what the caller does. */
    public boolean wouldBlock() {
        return active && decision.block();
    }
}
