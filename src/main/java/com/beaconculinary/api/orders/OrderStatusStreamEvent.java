package com.beaconculinary.api.orders;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class OrderStatusStreamEvent {
    private OrderEventType eventType;
    private OrderSummaryDto order;

    // When the status changed (the audit row's changed_at), for listeners such as the public
    // board; null for ORDER_UPDATED. Not part of the kitchen stream payload.
    @JsonIgnore
    private Instant changedAt;

    public OrderStatusStreamEvent(OrderEventType eventType, OrderSummaryDto order) {
        this(eventType, order, null);
    }
}
