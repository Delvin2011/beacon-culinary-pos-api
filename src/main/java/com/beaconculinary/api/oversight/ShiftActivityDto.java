package com.beaconculinary.api.oversight;

import com.beaconculinary.api.orders.OrderAdjustmentReasonCode;
import com.beaconculinary.api.orders.OrderStatus;
import com.beaconculinary.api.orders.PaymentMethod;
import com.beaconculinary.api.orders.RefundMethod;
import com.beaconculinary.api.shifts.ShiftVarianceReasonCode;
import com.beaconculinary.api.users.UserRefDto;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** POS Oversight A3: GET /admin/shifts/{id}/activity. */
public record ShiftActivityDto(
        ShiftDetail shift,
        ExpectedCashBreakdown expectedCashBreakdown,
        Totals totals,
        List<SoldOrder> orders,
        List<ProcessedAdjustment> adjustments) {

    /** The list-row fields plus close and variance details. */
    public record ShiftDetail(
            Long shiftId,
            UserRefDto owner,
            ShiftDisplayStatus displayStatus,
            Instant openedAt,
            Instant closedAt,
            BigDecimal openingFloat,
            BigDecimal closingCash,
            BigDecimal expectedCash,
            BigDecimal variance,
            long orderCount,
            UserRefDto closedBy,
            ShiftVarianceReasonCode varianceReasonCode,
            String varianceNote,
            UserRefDto varianceAuthorizedBy) {
    }

    /** openingFloat + cashSales - cashRefundsProcessed = expectedCash, always. "Refunds" here is
     * every cash-paid adjustment processed in the shift (voids, refunds and discounts), the same
     * term the cashup summary calls adjustmentsTotal. */
    public record ExpectedCashBreakdown(
            BigDecimal openingFloat,
            BigDecimal cashSales,
            BigDecimal cashRefundsProcessed,
            BigDecimal expectedCash,
            BreakdownSource source,
            Boolean breakdownMismatch,
            @JsonInclude(JsonInclude.Include.NON_NULL) RecomputedParts recomputed,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal storedExpectedCash) {
    }

    public record RecomputedParts(BigDecimal cashSales, BigDecimal cashRefundsProcessed, BigDecimal expectedCash) {
    }

    /** orderCount and salesByMethod cover orders sold in the shift; the four adjustment
     * buckets cover adjustments processed in it (the adjustments list). */
    public record Totals(
            long orderCount,
            Map<PaymentMethod, BigDecimal> salesByMethod,
            CountAmount voids,
            CountAmount refunds,
            CountAmount discounts,
            CountAmount extrasRemoved) {
    }

    public record CountAmount(long count, BigDecimal amount) {
    }

    public record SoldOrder(
            Long orderId,
            Integer orderNumber,
            Instant createdAt,
            UserRefDto rungBy,
            OrderStatus status,
            BigDecimal originalTotal,
            BigDecimal total,
            List<Payment> payments,
            boolean discounted,
            boolean extrasRemoved) {
    }

    /** Method and amount only — card references are never exposed. */
    public record Payment(PaymentMethod method, BigDecimal amount) {
    }

    public record ProcessedAdjustment(
            Long adjustmentId,
            Instant createdAt,
            Long orderId,
            Integer orderNumber,
            AdjustmentType type,
            BigDecimal amount,
            RefundMethod refundMethod,
            OrderAdjustmentReasonCode reasonCode,
            String note,
            UserRefDto requestedBy,
            UserRefDto authorizedBy,
            Long orderSoldInShiftId,
            boolean crossShift) {
    }
}
