package com.beaconculinary.api.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface OrderAdjustmentRepository extends JpaRepository<OrderAdjustment, Long> {
    /** Stage 2.5/4 expected-cash formula's subtraction term — adjustments are attributed to the
     * shift open when they were authorized, which may differ from the order's own shift.
     * Stage 4 Part D narrows this to CASH-payout adjustments only: an ACCOUNT_BALANCE-refunded
     * adjustment has zero cash-drawer impact, since no physical cash moved. */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM OrderAdjustment a WHERE a.shift.id = :shiftId AND a.refundMethod = :refundMethod")
    BigDecimal sumAmountByShiftIdAndRefundMethod(@Param("shiftId") Long shiftId, @Param("refundMethod") RefundMethod refundMethod);

    /** Stage 4 Part B balance formula's totalReversed term. */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM OrderAdjustment a WHERE a.refundMethod = 'ACCOUNT_BALANCE' AND a.account.id = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") Long accountId);
}
