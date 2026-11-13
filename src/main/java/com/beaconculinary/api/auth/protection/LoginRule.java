package com.beaconculinary.api.auth.protection;

/** The WS1-1 rules (decision D3). The name is stored in login_attempts.rule_triggered. */
public enum LoginRule {
    /** Per email (including unknown emails): failures in the window lock the account, escalating. */
    PASSWORD_ACCOUNT(LoginMethod.PASSWORD, Effect.LOCK, false),
    /** Per cashierId and client IP: failures in the window block that pair for a fixed time. */
    PIN_PAIR(LoginMethod.PIN, Effect.BLOCK, false),
    /** Per cashierId from any source: a higher ceiling that locks the account's PIN, escalating, and alerts. */
    PIN_ACCOUNT_CEILING(LoginMethod.PIN, Effect.LOCK, true),
    /** Per calling user: failed manager-PIN entries block that caller for a fixed time. */
    MANAGER_PIN_CALLER(LoginMethod.MANAGER_PIN, Effect.BLOCK, false),
    /** All callers together: alert only, never blocks. */
    MANAGER_PIN_GLOBAL(LoginMethod.MANAGER_PIN, Effect.ALERT, true),
    /** Per user: wrong old passwords block change-password for a fixed time (login is unaffected). */
    CHANGE_PASSWORD_USER(LoginMethod.CHANGE_PASSWORD, Effect.BLOCK, false);

    public enum Effect {
        /** Temporary block for the rule's key, fixed duration. */
        BLOCK,
        /** Account lock for the method, escalating 1st, 2nd, then admin unlock. */
        LOCK,
        /** Logged for alerting only. */
        ALERT
    }

    private final LoginMethod method;
    private final Effect effect;
    private final boolean alerts;

    LoginRule(LoginMethod method, Effect effect, boolean alerts) {
        this.method = method;
        this.effect = effect;
        this.alerts = alerts;
    }

    public LoginMethod method() {
        return method;
    }

    public Effect effect() {
        return effect;
    }

    /** Whether triggering this rule also writes an alert (WARN) for monitoring. */
    public boolean alerts() {
        return alerts;
    }
}
