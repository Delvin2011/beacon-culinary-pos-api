package com.beaconculinary.api.board;

import com.beaconculinary.api.orders.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

/** Deliberately minimal — no line items, no internal order id, no cashier/customer data. */
@Data
@AllArgsConstructor
public class PublicOrderDto {
    private Integer orderNumber;
    private OrderStatus status;
}
