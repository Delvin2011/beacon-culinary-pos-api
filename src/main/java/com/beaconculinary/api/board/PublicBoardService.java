package com.beaconculinary.api.board;

import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatus;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.orders.OrderStatusStreamEvent;
import lombok.AllArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class PublicBoardService {
    // Same three states as the kitchen screen, on purpose. VOIDED/REFUNDED (Stage 2.6) are
    // excluded by construction — they're simply never in this list, so nothing changes here
    // once that stage starts producing them.
    private static final List<OrderStatus> BOARD_STATUSES = List.of(OrderStatus.PENDING, OrderStatus.IN_PROGRESS, OrderStatus.DONE);

    private final OrderRepository orderRepository;
    private final OrderStatusEventRepository orderStatusEventRepository;
    private final PublicBoardEventBroadcaster broadcaster;
    private final PublicBoardSettings settings;
    private final Clock clock;

    /** A DONE order stays for board.done-display-minutes after it was finished, then drops off.
     * A DONE order with no recorded DONE time (none are expected) stays, without a removeAt. */
    @Transactional(readOnly = true)
    public PublicBoardDto getTodayBoard() {
        var now = clock.instant();
        var today = LocalDate.now(clock);
        var orders = orderRepository.findByOrderDateAndStatusInOrderByCreatedAtAsc(today, BOARD_STATUSES);

        var doneIds = orders.stream().filter(order -> order.getStatus() == OrderStatus.DONE).map(order -> order.getId()).toList();
        Map<Long, Instant> doneAtById = doneIds.isEmpty() ? Map.of()
                : orderStatusEventRepository.findLastReachedAt(doneIds, OrderStatus.DONE).stream()
                        .collect(Collectors.toMap(OrderStatusEventRepository.StatusReachedAt::getOrderId,
                                reached -> UtcTimestamps.toInstant(reached.getChangedAt())));

        var board = orders.stream()
                .map(order -> toPublic(order.getOrderNumber(), order.getStatus(), doneAtById.get(order.getId())))
                .filter(order -> order.getRemoveAt() == null || now.isBefore(order.getRemoveAt()))
                .toList();
        return new PublicBoardDto(board, now, settings.doneDisplayMinutes());
    }

    public SseEmitter subscribe() {
        return broadcaster.subscribe();
    }

    // Reduces the shared full-detail event down to the public-safe shape before broadcasting —
    // the same source Stage 2.1's kitchen stream reacts to, just a different, minimal view of it.
    // A DONE event carries doneAt/removeAt, so open boards can time the order's removal.
    @EventListener
    public void onOrderStatusChanged(OrderStatusStreamEvent event) {
        var summary = event.getOrder();
        var doneAt = summary.getStatus() == OrderStatus.DONE ? event.getChangedAt() : null;
        var minimal = toPublic(summary.getOrderNumber(), summary.getStatus(), doneAt);
        broadcaster.broadcast(new PublicBoardStreamEvent(event.getEventType(), minimal));
    }

    private PublicOrderDto toPublic(Integer orderNumber, OrderStatus status, Instant doneAt) {
        if (status != OrderStatus.DONE || doneAt == null) {
            return new PublicOrderDto(orderNumber, status, null, null);
        }
        return new PublicOrderDto(orderNumber, status, doneAt, doneAt.plus(settings.doneDisplay()));
    }
}
