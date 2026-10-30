package com.beaconculinary.api.orders;

import com.beaconculinary.api.menu.Station;
import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * One station's part of an order (V97): KITCHEN or BAR, PENDING -> IN_PROGRESS -> DONE. An order
 * has a row per station its lines need (none for cold-only). Only OrderStationService writes
 * these, and it derives the order's own status from them.
 */
@Getter
@Setter
@Entity
@Table(name = "order_station_status")
@IdClass(OrderStationStatus.Key.class)
public class OrderStationStatus {
    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "station")
    private Station station;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private OrderStatus status = OrderStatus.PENDING;

    // UTC, from the application clock.
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // null until a person moves it on (and on rows backfilled by V97).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @Getter
    @Setter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long order;
        private Station station;
    }
}
