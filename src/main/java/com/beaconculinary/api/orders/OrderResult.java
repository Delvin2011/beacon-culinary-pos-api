package com.beaconculinary.api.orders;

/** O1: an order as returned by POST /orders or POST /orders/{id}/adjustments, and whether this
 * call created it (201) or replayed an earlier call with the same idempotency key (200). */
public record OrderResult(OrderDto order, boolean replayed) {
    static OrderResult created(OrderDto order) {
        return new OrderResult(order, false);
    }

    static OrderResult replayed(OrderDto order) {
        return new OrderResult(order, true);
    }
}
