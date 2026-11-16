package com.beaconculinary.api.orders;

/** O1: 409 ORDER_NUMBER_CONFLICT. The sale couldn't be given an order number (the day's number
 * lock wasn't granted in time, or a number clashed twice). Nothing was saved; safe to retry with
 * the same clientOrderId. */
public class OrderNumberConflictException extends RuntimeException {
    public OrderNumberConflictException() {
        super("The order couldn't be numbered right now. Nothing was saved; please try again.");
    }
}
