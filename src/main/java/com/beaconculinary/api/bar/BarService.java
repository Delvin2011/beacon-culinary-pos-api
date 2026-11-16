package com.beaconculinary.api.bar;

import com.beaconculinary.api.menu.Station;
import com.beaconculinary.api.orders.OrderStationService;
import com.beaconculinary.api.orders.OrderStationStatusRepository;
import com.beaconculinary.api.orders.OrderStatus;
import com.beaconculinary.api.orders.OrderStatusStreamEvent;
import com.beaconculinary.api.orders.OrderSummaryDto;
import com.beaconculinary.api.orders.StationViewChangedEvent;
import com.beaconculinary.api.orders.StationViews;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** The bar display, a mirror of the kitchen's for the BAR station: today's orders with a bar part
 * still PENDING or IN_PROGRESS, showing only bar lines plus otherStations. */
@Service
@AllArgsConstructor
public class BarService {
    private final OrderStationStatusRepository orderStationStatusRepository;
    private final OrderStationService orderStationService;
    private final StationViews stationViews;
    private final BarEventBroadcaster barEventBroadcaster;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<OrderSummaryDto> getActiveOrders() {
        var today = LocalDate.now(clock);
        return orderStationStatusRepository.findWork(Station.BAR, OrderStationService.DISPLAY_STATUSES, today).stream()
                .map(row -> stationViews.view(row.getOrder(), Station.BAR))
                .toList();
    }

    @Transactional
    public OrderSummaryDto updateStatus(Long orderId, OrderStatus requestedStatus) {
        var order = orderStationService.advance(orderId, Station.BAR, requestedStatus);
        return stationViews.view(order, Station.BAR);
    }

    public SseEmitter subscribe() {
        return barEventBroadcaster.subscribe();
    }

    // Sends the bar's view of any order that has a bar part — never the kitchen's lines.
    // O1: only after the transaction commits, so a rolled-back sale or status change never
    // reaches the display. With no transaction (none today) it sends at once.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStationViewChanged(StationViewChangedEvent event) {
        var view = event.viewFor(Station.BAR);
        if (view != null) {
            barEventBroadcaster.broadcast(new OrderStatusStreamEvent(event.eventType(), view));
        }
    }
}
