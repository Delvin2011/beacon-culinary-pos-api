package com.beaconculinary.api.admin;

public class InvalidPinException extends RuntimeException {
    public InvalidPinException() {
        super("Invalid PIN.");
    }
}
