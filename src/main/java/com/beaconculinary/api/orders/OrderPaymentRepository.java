package com.beaconculinary.api.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface OrderPaymentRepository extends JpaRepository<OrderPayment, Long> {
    /** Stage 4 Part D expected-cash formula's addition term — actual cash collected at sale
     * time for this shift, immune to later adjustments since this row is never mutated. */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM OrderPayment p WHERE p.order.shift.id = :shiftId AND p.method = :method")
    BigDecimal sumAmountByShiftIdAndMethod(@Param("shiftId") Long shiftId, @Param("method") PaymentMethod method);

    /** Stage 4 Part B balance formula's totalCharged term. */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM OrderPayment p WHERE p.method = 'ACCOUNT' AND p.account.id = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") Long accountId);
}
