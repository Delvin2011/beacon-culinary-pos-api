package com.beaconculinary.api.orders;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OrderStatusStreamEvent {
    private OrderEventType eventType;
    private OrderSummaryDto order;
}
