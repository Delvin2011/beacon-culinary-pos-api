package com.beaconculinary.api.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface OrderStatusEventRepository extends JpaRepository<OrderStatusEvent, Long> {
    /** When each order last moved into a status (UTC). */
    interface StatusReachedAt {
        Long getOrderId();

        LocalDateTime getChangedAt();
    }

    @Query("SELECT e.order.id AS orderId, MAX(e.changedAt) AS changedAt FROM OrderStatusEvent e "
            + "WHERE e.order.id IN :orderIds AND e.toStatus = :status GROUP BY e.order.id")
    List<StatusReachedAt> findLastReachedAt(@Param("orderIds") Collection<Long> orderIds, @Param("status") OrderStatus status);
}
