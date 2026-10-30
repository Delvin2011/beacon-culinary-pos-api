package com.beaconculinary.api.orders;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.menu.Station;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Single choke point for "an order's status changed": writes the audit row and publishes a
 * Spring application event carrying the same data, so any number of downstream views (kitchen,
 * public board, ...) can react without this class ever needing to know they exist — one source
 * of truth, arbitrarily many independent subscribers, never two independently-maintained
 * broadcast mechanisms. Called both from order creation (the implicit {@code null -> PENDING}
 * transition) and from the kitchen status-change endpoint.
 */
@Component
@AllArgsConstructor
public class OrderStatusEventPublisher {
    private final OrderStatusEventRepository orderStatusEventRepository;
    private final OrderSummaryMapper orderSummaryMapper;
    private final AuthService authService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Clock clock;

    @Transactional
    public void publish(Order order, OrderStatus fromStatus) {
        var changedAt = clock.instant();
        var event = new OrderStatusEvent();
        event.setOrder(order);
        event.setFromStatus(fromStatus);
        event.setToStatus(order.getStatus());
        event.setChangedBy(authService.getCurrentUser());
        event.setChangedAt(UtcTimestamps.toUtc(changedAt));
        orderStatusEventRepository.save(event);

        var eventType = fromStatus == null ? OrderEventType.ORDER_CREATED : OrderEventType.STATUS_CHANGED;
        applicationEventPublisher.publishEvent(new OrderStatusStreamEvent(eventType, orderSummaryMapper.toDto(order), changedAt));
        applicationEventPublisher.publishEvent(new StationViewChangedEvent(order, eventType));
    }

    /** A station's own status change (KITCHEN or BAR): the audit row, with its station. The
     * displays are told by the caller — through {@link #publish} when the order's own status
     * changed too, or a StationViewChangedEvent when it didn't. */
    @Transactional
    public void logStationChange(Order order, Station station, OrderStatus fromStatus, OrderStatus toStatus) {
        var event = new OrderStatusEvent();
        event.setOrder(order);
        event.setStation(station);
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setChangedBy(authService.getCurrentUser());
        event.setChangedAt(UtcTimestamps.nowUtc(clock));
        orderStatusEventRepository.save(event);
    }
}
