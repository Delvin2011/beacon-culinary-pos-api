package com.beaconculinary.api.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Query("SELECT COALESCE(MAX(o.orderNumber), 0) FROM Order o WHERE o.orderDate = :orderDate")
    int findMaxOrderNumberForDate(@Param("orderDate") LocalDate orderDate);

    List<Order> findByOrderDateOrderByOrderNumberDesc(LocalDate orderDate);

    List<Order> findByOrderDateAndStatusInOrderByCreatedAtAsc(LocalDate orderDate, List<OrderStatus> statuses);

    long countByShiftId(Long shiftId);

    /**
     * Conditional guard against double-applying an EXTRAS_ONLY adjustment — same anti-race
     * pattern as the stock decrements: returns rows affected (0 or 1) so the caller can detect
     * a concurrent/duplicate attempt without a separate read-then-write race window.
     */
    @Modifying
    @Query("UPDATE Order o SET o.extrasAdjusted = true WHERE o.id = :id AND o.extrasAdjusted = false")
    int markExtrasAdjusted(@Param("id") Long id);
}
