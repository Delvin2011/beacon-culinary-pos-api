package com.beaconculinary.api.kitchen;

import com.beaconculinary.api.orders.OrderStatus;

public class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException(OrderStatus from, OrderStatus to) {
        super("Cannot transition order from " + from + " to " + to + ".");
    }
}
