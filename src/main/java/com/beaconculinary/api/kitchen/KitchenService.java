package com.beaconculinary.api.kitchen;

import com.beaconculinary.api.orders.*;
import lombok.AllArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
public class KitchenService {
    private static final List<OrderStatus> ACTIVE_STATUSES = List.of(OrderStatus.PENDING, OrderStatus.IN_PROGRESS);

    // The only transitions this endpoint may perform, one step at a time. COLLECTED (Stage 2.3)
    // and VOIDED/REFUNDED (Stage 2.6) are other endpoints' jobs, not reachable from here.
    private static final Map<OrderStatus, OrderStatus> ALLOWED_NEXT_STATUS = Map.of(
            OrderStatus.PENDING, OrderStatus.IN_PROGRESS,
            OrderStatus.IN_PROGRESS, OrderStatus.DONE
    );

    private final OrderRepository orderRepository;
    private final OrderSummaryMapper orderSummaryMapper;
    private final OrderStatusEventPublisher orderStatusEventPublisher;
    private final OrderEventBroadcaster orderEventBroadcaster;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<OrderSummaryDto> getActiveOrders() {
        var today = LocalDate.now(clock);
        return orderRepository.findByOrderDateAndStatusInOrderByCreatedAtAsc(today, ACTIVE_STATUSES)
                .stream().map(orderSummaryMapper::toDto).toList();
    }

    @Transactional
    public OrderSummaryDto updateStatus(Long orderId, OrderStatus requestedStatus) {
        var order = orderRepository.findById(orderId).orElseThrow(OrderNotFoundException::new);
        var currentStatus = order.getStatus();

        if (ALLOWED_NEXT_STATUS.get(currentStatus) != requestedStatus) {
            throw new InvalidStatusTransitionException(currentStatus, requestedStatus);
        }

        order.setStatus(requestedStatus);
        orderRepository.save(order);
        orderStatusEventPublisher.publish(order, currentStatus);

        return orderSummaryMapper.toDto(order);
    }

    public SseEmitter subscribe() {
        return orderEventBroadcaster.subscribe();
    }

    // Relays the shared order-status event onto the kitchen's own SSE channel — the full,
    // un-filtered view (unlike the public board's minimal one).
    @EventListener
    public void onOrderStatusChanged(OrderStatusStreamEvent event) {
        orderEventBroadcaster.broadcast(event);
    }
}
