package com.beaconculinary.api.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface OrderAdjustmentRepository extends JpaRepository<OrderAdjustment, Long> {
    /** Stage 2.5/4 expected-cash formula's subtraction term — adjustments are attributed to the
     * shift open when they were authorized, which may differ from the order's own shift.
     * Stage 4 Part D narrows this to CASH-payout adjustments only: an ACCOUNT_BALANCE-refunded
     * adjustment has zero cash-drawer impact, since no physical cash moved. */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM OrderAdjustment a WHERE a.shift.id = :shiftId AND a.refundMethod = :refundMethod")
    BigDecimal sumAmountByShiftIdAndRefundMethod(@Param("shiftId") Long shiftId, @Param("refundMethod") RefundMethod refundMethod);

    /** POS Oversight A4: adjustments processed in a shift (the cash-out attribution), which may
     * be on orders sold in an earlier shift. */
    @Query("SELECT a FROM OrderAdjustment a JOIN FETCH a.order JOIN FETCH a.requestedBy JOIN FETCH a.authorizedBy "
            + "WHERE a.shift.id = :shiftId ORDER BY a.createdAt, a.id")
    List<OrderAdjustment> findProcessedInShift(@Param("shiftId") Long shiftId);

    /** POS Oversight A4: every adjustment on orders sold in a shift, whichever shift processed
     * it — feeds the per-order discounted/extrasRemoved flags. */
    @Query("SELECT a FROM OrderAdjustment a WHERE a.order.shift.id = :shiftId")
    List<OrderAdjustment> findOnOrdersSoldInShift(@Param("shiftId") Long shiftId);

    /** Stage 4 Part B balance formula's totalReversed term. */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM OrderAdjustment a WHERE a.refundMethod = 'ACCOUNT_BALANCE' AND a.account.id = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") Long accountId);
}
