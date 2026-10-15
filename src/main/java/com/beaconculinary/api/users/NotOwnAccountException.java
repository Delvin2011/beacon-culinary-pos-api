package com.beaconculinary.api.users;

/** The caller may not act on another user's account (e.g. change someone else's password). */
public class NotOwnAccountException extends RuntimeException {
    public NotOwnAccountException(String message) {
        super(message);
    }
}
