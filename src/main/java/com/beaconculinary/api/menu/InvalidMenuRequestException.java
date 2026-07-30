package com.beaconculinary.api.menu;

public class InvalidMenuRequestException extends RuntimeException {
    public InvalidMenuRequestException(String message) {
        super(message);
    }
}
