package com.beaconculinary.api.shifts;

public class InvalidShiftRequestException extends RuntimeException {
    public InvalidShiftRequestException(String message) {
        super(message);
    }
}