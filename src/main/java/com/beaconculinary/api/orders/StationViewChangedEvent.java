package com.beaconculinary.api.orders;

/** Internal: an order changed in a way the station displays (kitchen, bar) must show — created,
 * a station or overall status change, or extras removed. Each display listener sends its own view
 * of the order, and only when the order has that station. The public board listens to
 * OrderStatusStreamEvent instead, which fires only for overall status changes. */
public record StationViewChangedEvent(Order order, OrderEventType eventType) {
}
