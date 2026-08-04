package com.beaconculinary.api.board;

import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatus;
import com.beaconculinary.api.orders.OrderStatusStreamEvent;
import lombok.AllArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
@AllArgsConstructor
public class PublicBoardService {
    // Same three states as the kitchen screen, on purpose. VOIDED/REFUNDED (Stage 2.6) are
    // excluded by construction — they're simply never in this list, so nothing changes here
    // once that stage starts producing them.
    private static final List<OrderStatus> BOARD_STATUSES = List.of(OrderStatus.PENDING, OrderStatus.IN_PROGRESS, OrderStatus.DONE);

    private final OrderRepository orderRepository;
    private final PublicBoardEventBroadcaster broadcaster;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PublicBoardDto getTodayBoard() {
        var today = LocalDate.now(clock);
        var orders = orderRepository.findByOrderDateAndStatusInOrderByCreatedAtAsc(today, BOARD_STATUSES).stream()
                .map(order -> new PublicOrderDto(order.getOrderNumber(), order.getStatus()))
                .toList();
        return new PublicBoardDto(orders);
    }

    public SseEmitter subscribe() {
        return broadcaster.subscribe();
    }

    // Reduces the shared full-detail event down to the public-safe shape before broadcasting —
    // the same source Stage 2.1's kitchen stream reacts to, just a different, minimal view of it.
    @EventListener
    public void onOrderStatusChanged(OrderStatusStreamEvent event) {
        var summary = event.getOrder();
        var minimal = new PublicOrderDto(summary.getOrderNumber(), summary.getStatus());
        broadcaster.broadcast(new PublicBoardStreamEvent(event.getEventType(), minimal));
    }
}
