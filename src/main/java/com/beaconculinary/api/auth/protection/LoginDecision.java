package com.beaconculinary.api.auth.protection;

import java.time.Duration;

/**
 * The result of evaluating the rules before a credential check.
 *
 * @param block          whether a rule would block this attempt
 * @param rule           the blocking rule, or the alert-only rule when nothing blocks; null when nothing triggered
 * @param alert          a rule that also raises an alert (may equal {@code rule}); null when none
 * @param retryAfter     how long the block or lock would last; null for an admin-unlock lock or no block
 * @param wouldLockLevel 1, 2 or 3 (admin unlock) for a LOCK rule; null otherwise
 */
public record LoginDecision(boolean block, LoginRule rule, LoginRule alert, Duration retryAfter, Integer wouldLockLevel) {
    public static final LoginDecision ALLOW = new LoginDecision(false, null, null, null, null);
}
