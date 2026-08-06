package com.beaconculinary.api.orders;

public class OrderAlreadyAdjustedException extends RuntimeException {
    public OrderAlreadyAdjustedException() {
        super("Order is already voided or refunded.");
    }
}
