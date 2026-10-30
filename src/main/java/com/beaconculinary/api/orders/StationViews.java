package com.beaconculinary.api.orders;

import com.beaconculinary.api.menu.Station;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * A station display's view of an order: only that station's lines, its own status, the order's
 * overall status, and the other stations' progress (otherStations). Once the order is collected,
 * voided or refunded, status is that overall status instead, so the display drops it.
 */
@Component
@AllArgsConstructor
public class StationViews {
    private static final Set<OrderStatus> OUT_OF_FLOW = EnumSet.of(OrderStatus.COLLECTED, OrderStatus.VOIDED, OrderStatus.REFUNDED);

    private final OrderSummaryMapper orderSummaryMapper;

    public boolean has(Order order, Station station) {
        return row(order, station).isPresent();
    }

    public OrderSummaryDto view(Order order, Station station) {
        var own = row(order, station).orElseThrow(OrderNotFoundException::new);
        var summary = orderSummaryMapper.toDto(order);
        summary.setLines(summary.getLines().stream().filter(line -> line.getStation() == station).toList());
        summary.setOrderStatus(order.getStatus());
        summary.setStatus(OUT_OF_FLOW.contains(order.getStatus()) ? order.getStatus() : own.getStatus());
        summary.setOtherStations(order.getStationStatuses().stream()
                .filter(other -> other.getStation() != station)
                .map(other -> new StationStatusDto(other.getStation(), other.getStatus()))
                .toList());
        return summary;
    }

    private static Optional<OrderStationStatus> row(Order order, Station station) {
        return order.getStationStatuses().stream().filter(row -> row.getStation() == station).findFirst();
    }
}
