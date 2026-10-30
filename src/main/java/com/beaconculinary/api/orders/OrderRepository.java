package com.beaconculinary.api.orders;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    /** Locks the order row: the status engine reads every station of it, so two stations moving
     * the same order at once must take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT COALESCE(MAX(o.orderNumber), 0) FROM Order o WHERE o.orderDate = :orderDate")
    int findMaxOrderNumberForDate(@Param("orderDate") LocalDate orderDate);

    List<Order> findByOrderDateOrderByOrderNumberDesc(LocalDate orderDate);

    List<Order> findByOrderDateAndStatusInOrderByCreatedAtAsc(LocalDate orderDate, List<OrderStatus> statuses);

    long countByShiftId(Long shiftId);

    /** POS Oversight A4: sale-order counts for a page of shifts in one grouped query (no N+1);
     * complimentary orders are not counted.
     * Each row is [shiftId (Long), count (Long)]; shifts with no orders are absent. */
    @Query("SELECT o.shift.id, COUNT(o) FROM Order o WHERE o.shift.id IN :shiftIds"
            + " AND o.orderKind = com.beaconculinary.api.orders.OrderKind.SALE GROUP BY o.shift.id")
    List<Object[]> countByShiftIds(@Param("shiftIds") Collection<Long> shiftIds);

    /** POS Oversight A4: orders sold in a shift with who rang them up and their payments. */
    @Query("SELECT DISTINCT o FROM Order o JOIN FETCH o.cashier LEFT JOIN FETCH o.payments "
            + "WHERE o.shift.id = :shiftId ORDER BY o.createdAt, o.id")
    List<Order> findSoldInShiftWithPayments(@Param("shiftId") Long shiftId);

    /**
     * Conditional guard against double-applying an EXTRAS_ONLY adjustment — same anti-race
     * pattern as the stock decrements: returns rows affected (0 or 1) so the caller can detect
     * a concurrent/duplicate attempt without a separate read-then-write race window.
     */
    @Modifying
    @Query("UPDATE Order o SET o.extrasAdjusted = true WHERE o.id = :id AND o.extrasAdjusted = false")
    int markExtrasAdjusted(@Param("id") Long id);
}
