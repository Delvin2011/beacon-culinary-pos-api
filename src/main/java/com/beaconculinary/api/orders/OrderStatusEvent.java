package com.beaconculinary.api.orders;

import com.beaconculinary.api.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "order_status_events")
public class OrderStatusEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    // Null for the implicit order-creation event (no prior status).
    @Column(name = "from_status")
    @Enumerated(EnumType.STRING)
    private OrderStatus fromStatus;

    @Column(name = "to_status")
    @Enumerated(EnumType.STRING)
    private OrderStatus toStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by")
    private User changedBy;

    // UTC, set from the application clock by OrderStatusEventPublisher (the column's
    // SYSUTCDATETIME() default only covers rows written elsewhere).
    @Column(name = "changed_at", updatable = false)
    private LocalDateTime changedAt;
}
