package com.beaconculinary.api.orders;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.menu.Station;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The status engine: the only writer of an order's station rows, and of its own status for the
 * preparation states (PENDING, IN_PROGRESS, DONE). Each station moves forward one step at a time
 * (PENDING -> IN_PROGRESS -> DONE); the order's status is derived from all its stations:
 *
 * <ul>
 *   <li>all PENDING -> PENDING</li>
 *   <li>all DONE -> DONE</li>
 *   <li>anything else (one started, or some DONE and others PENDING) -> IN_PROGRESS</li>
 * </ul>
 *
 * A station change is logged with its station; a change of the order's status is logged and
 * published as before (station NULL), so the public board, reports, voids and collection keep
 * reading orders.status unchanged.
 */
@Service
@AllArgsConstructor
public class OrderStationService {
    private static final Map<OrderStatus, OrderStatus> NEXT = Map.of(
            OrderStatus.PENDING, OrderStatus.IN_PROGRESS,
            OrderStatus.IN_PROGRESS, OrderStatus.DONE);
    private static final Set<OrderStatus> OUT_OF_FLOW = EnumSet.of(OrderStatus.COLLECTED, OrderStatus.VOIDED, OrderStatus.REFUNDED);

    private final OrderRepository orderRepository;
    private final OrderStatusEventPublisher orderStatusEventPublisher;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final AuthService authService;
    private final Clock clock;

    /**
     * The stations a new order needs: its lines' stations, without NONE. The order is PENDING with
     * a PENDING row per station; with none (cold drinks only, handed over at payment) it is
     * COLLECTED at once, with no rows. Called before the order is saved and its creation published.
     */
    public void startStations(Order order) {
        var stations = order.getLines().stream().map(OrderLine::getStation).filter(Station::prepares).distinct().sorted().toList();
        if (stations.isEmpty()) {
            order.setStatus(OrderStatus.COLLECTED);
            return;
        }
        order.setStatus(OrderStatus.PENDING);
        var now = UtcTimestamps.nowUtc(clock);
        for (var station : stations) {
            var row = new OrderStationStatus();
            row.setOrder(order);
            row.setStation(station);
            row.setStatus(OrderStatus.PENDING);
            row.setUpdatedAt(now);
            order.getStationStatuses().add(row);
        }
    }

    /** Moves one station of an order one step forward and re-derives the order's status. 404 when
     * the order has no row for the station; 400 for a skipped or backward step, or when the order
     * is already collected, voided or refunded. */
    @Transactional
    public Order advance(Long orderId, Station station, OrderStatus requested) {
        var order = orderRepository.findByIdForUpdate(orderId).orElseThrow(OrderNotFoundException::new);
        var row = order.getStationStatuses().stream().filter(candidate -> candidate.getStation() == station)
                .findFirst().orElseThrow(OrderNotFoundException::new);
        if (OUT_OF_FLOW.contains(order.getStatus())) {
            throw new StationTransitionException(order.getStatus(), requested);
        }
        var from = row.getStatus();
        if (NEXT.get(from) != requested) {
            throw new StationTransitionException(from, requested);
        }

        row.setStatus(requested);
        row.setUpdatedAt(UtcTimestamps.nowUtc(clock));
        row.setUpdatedBy(authService.getCurrentUser());
        orderStatusEventPublisher.logStationChange(order, station, from, requested);

        var overall = derive(order.getStationStatuses().stream().map(OrderStationStatus::getStatus).toList());
        var previous = order.getStatus();
        if (overall != previous) {
            order.setStatus(overall);
            orderRepository.save(order);
            orderStatusEventPublisher.publish(order, previous);
        } else {
            orderRepository.save(order);
            applicationEventPublisher.publishEvent(new StationViewChangedEvent(order, OrderEventType.STATUS_CHANGED));
        }
        return order;
    }

    /** The order's status from its stations' statuses (see the class comment). */
    static OrderStatus derive(Collection<OrderStatus> stationStatuses) {
        if (stationStatuses.stream().allMatch(status -> status == OrderStatus.PENDING)) {
            return OrderStatus.PENDING;
        }
        if (stationStatuses.stream().allMatch(status -> status == OrderStatus.DONE)) {
            return OrderStatus.DONE;
        }
        return OrderStatus.IN_PROGRESS;
    }

    /** The statuses a station display lists: its own work still to do. */
    public static final List<OrderStatus> DISPLAY_STATUSES = List.of(OrderStatus.PENDING, OrderStatus.IN_PROGRESS);
}
