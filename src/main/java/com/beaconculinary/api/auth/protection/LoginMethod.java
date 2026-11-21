package com.beaconculinary.api.auth.protection;

/** The credential checks that login protection covers. Stored as login_attempts.method and user_auth_state.scope. */
public enum LoginMethod {
    /** POST /auth/login (email and password). */
    PASSWORD,
    /** POST /auth/pin-login (cashierId and PIN). */
    PIN,
    /** POST /admin/authorize and /admin/authorize-session (manager PIN, PIN only). */
    MANAGER_PIN,
    /** POST /users/{id}/change-password (old password check). */
    CHANGE_PASSWORD,
    /** T0-6a: POST /auth/invites/lookup and /accept. Recorded per client IP; never an account scope. */
    INVITE;

    /** The scopes kept in user_auth_state (locks, last success): every method except INVITE. */
    public static java.util.EnumSet<LoginMethod> accountScopes() {
        return java.util.EnumSet.of(PASSWORD, PIN, MANAGER_PIN, CHANGE_PASSWORD);
    }
}
