package com.beaconculinary.api.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface OrderAdjustmentRepository extends JpaRepository<OrderAdjustment, Long> {
    /** Stage 2.5 expected-cash formula — adjustments are attributed to the shift open when
     * they were authorized, which may differ from the order's own shift. */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM OrderAdjustment a WHERE a.shift.id = :shiftId")
    BigDecimal sumAmountByShiftId(@Param("shiftId") Long shiftId);
}
