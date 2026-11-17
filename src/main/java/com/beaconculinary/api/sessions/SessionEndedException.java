package com.beaconculinary.api.sessions;

/** O2: the request's session has ended (or the token has none): 401 SESSION_ENDED with details.reason. */
public class SessionEndedException extends RuntimeException {
    private final SessionEndReason reason;

    public SessionEndedException(SessionEndReason reason) {
        super(reason.message());
        this.reason = reason;
    }

    public SessionEndReason getReason() {
        return reason;
    }
}
