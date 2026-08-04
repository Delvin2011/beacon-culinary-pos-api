package com.beaconculinary.api.board;

import com.beaconculinary.api.orders.OrderEventType;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PublicBoardStreamEvent {
    private OrderEventType eventType;
    private PublicOrderDto order;
}
