package com.beaconculinary.api.sessions;

/** O2 R7: why a session ended (user_sessions.end_reason), with the message shown on SESSION_ENDED. */
public enum SessionEndReason {
    LOGOUT("You signed out. Sign in again."),
    LOCK("This till was locked. Sign in again."),
    REPLACED("You were signed out because a newer sign-in replaced this one."),
    IDLE("You were signed out after a period without activity. Sign in again."),
    ADMIN("A manager ended this session. Sign in again."),
    SHIFT_FORCE_CLOSED("A manager closed your shift. Sign in again."),
    EXPIRED("Your session has expired. Sign in again."),
    /** T0-6a: an invite set a new password or PIN; every session of the person ends. */
    CREDENTIALS_CHANGED("Your sign-in details were changed. Sign in again.");

    private final String message;

    SessionEndReason(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
