package com.beaconculinary.api.orders;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** The KOT-equivalent view of an order — used both as the {@code GET /kitchen/orders} response shape and as the SSE payload body, so there's one serialization format for both. */
@Data
public class OrderSummaryDto {
    private Long orderId;
    private Integer orderNumber;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private List<OrderSummaryLineDto> lines;
}
