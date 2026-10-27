package com.beaconculinary.api.board;

import com.beaconculinary.api.orders.OrderStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/** Deliberately minimal — no line items, no internal order id, no cashier/customer data. doneAt
 * and removeAt are sent only for a DONE order: when it was finished, and when the board should
 * drop it (doneAt + board.done-display-minutes). */
@Data
@AllArgsConstructor
public class PublicOrderDto {
    private Integer orderNumber;
    private OrderStatus status;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Instant doneAt;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Instant removeAt;
}
