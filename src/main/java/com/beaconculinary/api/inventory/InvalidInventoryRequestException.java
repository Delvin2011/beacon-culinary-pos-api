package com.beaconculinary.api.inventory;

public class InvalidInventoryRequestException extends RuntimeException {
    public InvalidInventoryRequestException(String message) {
        super(message);
    }
}
