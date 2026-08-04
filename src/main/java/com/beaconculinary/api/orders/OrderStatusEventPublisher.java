package com.beaconculinary.api.orders;

import com.beaconculinary.api.auth.AuthService;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public void publish(Order order, OrderStatus fromStatus) {
        var event = new OrderStatusEvent();
        event.setOrder(order);
        event.setFromStatus(fromStatus);
        event.setToStatus(order.getStatus());
        event.setChangedBy(authService.getCurrentUser());
        orderStatusEventRepository.save(event);

        var eventType = fromStatus == null ? OrderEventType.ORDER_CREATED : OrderEventType.STATUS_CHANGED;
        applicationEventPublisher.publishEvent(new OrderStatusStreamEvent(eventType, orderSummaryMapper.toDto(order)));
    }
}
