package com.beaconculinary.api.kitchen;

import com.beaconculinary.api.menu.Station;
import com.beaconculinary.api.orders.*;
import lombok.AllArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * The kitchen display: today's orders with a KITCHEN station still PENDING or IN_PROGRESS, each
 * showing only its kitchen lines plus the other stations' progress (otherStations). The kitchen
 * moves its own station one step at a time; the status engine (OrderStationService) derives the
 * order's status. COLLECTED (Stage 2.3) and VOIDED/REFUNDED (Stage 2.6) are other endpoints' jobs.
 */
@Service
@AllArgsConstructor
public class KitchenService {
    private final OrderStationStatusRepository orderStationStatusRepository;
    private final OrderStationService orderStationService;
    private final StationViews stationViews;
    private final OrderEventBroadcaster orderEventBroadcaster;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<OrderSummaryDto> getActiveOrders() {
        var today = LocalDate.now(clock);
        return orderStationStatusRepository.findWork(Station.KITCHEN, OrderStationService.DISPLAY_STATUSES, today).stream()
                .map(row -> stationViews.view(row.getOrder(), Station.KITCHEN))
                .toList();
    }

    @Transactional
    public OrderSummaryDto updateStatus(Long orderId, OrderStatus requestedStatus) {
        var order = orderStationService.advance(orderId, Station.KITCHEN, requestedStatus);
        return stationViews.view(order, Station.KITCHEN);
    }

    public SseEmitter subscribe() {
        return orderEventBroadcaster.subscribe();
    }

    // Sends the kitchen's view of any order that has a kitchen part — never a bar-only or
    // cold-only order, and never the bar's lines.
    @EventListener
    public void onStationViewChanged(StationViewChangedEvent event) {
        if (stationViews.has(event.order(), Station.KITCHEN)) {
            orderEventBroadcaster.broadcast(new OrderStatusStreamEvent(event.eventType(), stationViews.view(event.order(), Station.KITCHEN)));
        }
    }
}
