package com.beaconculinary.api.auth.protection;

import java.time.Duration;

/**
 * The D3 rules as pure functions of failure counts, so they can be unit-tested without a database.
 *
 * <p>Counts are the failures already recorded in each rule's window, after the last success for
 * that method. A rule triggers when the count has reached its threshold: with the default of 5,
 * the 6th attempt in the window is the first one that would be blocked.
 */
public final class LoginRuleEvaluator {
    /** The highest lock level: the account stays locked until an admin unlocks it. */
    public static final int ADMIN_UNLOCK_LEVEL = 3;

    private LoginRuleEvaluator() {
    }

    /** The rules that trigger, before lock escalation is worked out. */
    public record Evaluation(LoginRule blocking, LoginRule alert) {
        public static final Evaluation NONE = new Evaluation(null, null);
    }

    /**
     * @param primary   PASSWORD: failures for the email. PIN: failures for cashierId + client IP.
     *                  MANAGER_PIN: failures by the caller. CHANGE_PASSWORD: failures by the user.
     * @param secondary PIN: failures for the cashierId from any source. MANAGER_PIN: failures by all
     *                  callers. Ignored otherwise.
     */
    public static Evaluation evaluate(LoginMethod method, long primary, long secondary, LoginProtectionProperties p) {
        return switch (method) {
            case PASSWORD -> reached(primary, p.getPasswordAccount())
                    ? new Evaluation(LoginRule.PASSWORD_ACCOUNT, null) : Evaluation.NONE;
            case PIN -> {
                if (reached(secondary, p.getPinAccountCeiling())) {
                    yield new Evaluation(LoginRule.PIN_ACCOUNT_CEILING, LoginRule.PIN_ACCOUNT_CEILING);
                }
                yield reached(primary, p.getPinPair()) ? new Evaluation(LoginRule.PIN_PAIR, null) : Evaluation.NONE;
            }
            case MANAGER_PIN -> new Evaluation(
                    reached(primary, p.getManagerPinCaller()) ? LoginRule.MANAGER_PIN_CALLER : null,
                    reached(secondary, p.getManagerPinGlobal()) ? LoginRule.MANAGER_PIN_GLOBAL : null);
            case CHANGE_PASSWORD -> reached(primary, p.getChangePasswordUser())
                    ? new Evaluation(LoginRule.CHANGE_PASSWORD_USER, null) : Evaluation.NONE;
        };
    }

    /** The level of the next lock, given how many locks already started in the escalation window. */
    public static int nextLockLevel(long priorLocks) {
        return (int) Math.min(ADMIN_UNLOCK_LEVEL, priorLocks + 1);
    }

    /** How long a lock at this level lasts; null means until an admin unlocks it. */
    public static Duration lockDuration(int level, LoginProtectionProperties p) {
        return level >= ADMIN_UNLOCK_LEVEL ? null : p.getLockDurations().get(level - 1);
    }

    private static boolean reached(long failures, LoginProtectionProperties.Threshold threshold) {
        return failures >= threshold.getMaxFailures();
    }
}
