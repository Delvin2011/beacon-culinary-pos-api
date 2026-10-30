package com.beaconculinary.api.orders;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** The KOT-equivalent view of an order — used both as the {@code GET /kitchen/orders} response shape and as the SSE payload body, so there's one serialization format for both.
 * On a station display (kitchen, bar) it is that station's view (StationViews): its lines only,
 * status = the station's own status, plus orderStatus and otherStations. */
@Data
public class OrderSummaryDto {
    private Long orderId;
    private Integer orderNumber;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private List<OrderSummaryLineDto> lines;

    // Station views only: the order's overall status, and the other stations' progress.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private OrderStatus orderStatus;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<StationStatusDto> otherStations;
}
