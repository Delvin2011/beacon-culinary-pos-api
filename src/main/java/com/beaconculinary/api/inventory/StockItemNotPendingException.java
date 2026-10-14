package com.beaconculinary.api.inventory;

/** A stock request or stock take can only be edited or cancelled while still pending. */
public class StockItemNotPendingException extends RuntimeException {
    public StockItemNotPendingException(String message) {
        super(message);
    }
}
