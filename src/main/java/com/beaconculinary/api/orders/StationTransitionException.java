package com.beaconculinary.api.orders;

/** A station update the status engine refuses: not the next step forward, or the order is already
 * collected, voided or refunded. */
public class StationTransitionException extends RuntimeException {
    public StationTransitionException(OrderStatus from, OrderStatus to) {
        super("Cannot transition order from " + from + " to " + to + ".");
    }
}
