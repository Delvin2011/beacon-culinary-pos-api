package com.beaconculinary.api.admin;

public class InvalidAuthorizationTokenException extends RuntimeException {
    public InvalidAuthorizationTokenException() {
        super("Authorization token is missing, expired, or already used.");
    }
}
