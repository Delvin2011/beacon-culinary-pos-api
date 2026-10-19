package com.beaconculinary.api.endofday;

/** A 400 for a malformed end-of-day request (e.g. a revoke without a note). */
public class InvalidEndOfDayRequestException extends RuntimeException {
    public InvalidEndOfDayRequestException(String message) {
        super(message);
    }
}
