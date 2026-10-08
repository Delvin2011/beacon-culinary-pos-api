package com.beaconculinary.api.shifts;

import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderPaymentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.PaymentMethod;
import com.beaconculinary.api.orders.RefundMethod;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single source of truth for a shift's expected cash, shared by the cashup summary, the
 * close (which snapshots its parts) and the admin activity view.
 *
 * <p>Stage 4 Part D — expected_cash = opening_float + SUM(CASH-method OrderPayment amounts on
 * orders sold in this shift) - SUM(CASH-refund_method adjustments processed in this shift).
 * Reworked from Stage 2.5's original orders.payment_method-based formula to account for
 * cash/card splits (Stage 4 Part A) and account payments (Part B): an ACCOUNT_BALANCE-refunded
 * adjustment is excluded entirely, since no physical cash moved. "Cash refunds" here covers
 * every cash-paid adjustment (voids, refunds and discounts alike).
 */
@Component
@AllArgsConstructor
public class ShiftCashCalculator {
    private final OrderRepository orderRepository;
    private final OrderPaymentRepository orderPaymentRepository;
    private final OrderAdjustmentRepository orderAdjustmentRepository;

    public CashBreakdown compute(Long shiftId, BigDecimal openingFloat) {
        var cashSales = orderPaymentRepository.sumAmountByShiftIdAndMethod(shiftId, PaymentMethod.CASH);
        var cashRefunds = orderAdjustmentRepository.sumAmountByShiftIdAndRefundMethod(shiftId, RefundMethod.CASH);
        var orderCount = orderRepository.countByShiftId(shiftId);
        return new CashBreakdown(openingFloat, cashSales, cashRefunds, openingFloat.add(cashSales).subtract(cashRefunds), orderCount);
    }

    /** The same formula for many shifts at once (three grouped queries, not three per shift),
     * keyed by shift id. openingFloats maps each shift id to its opening float. */
    public Map<Long, CashBreakdown> computeAll(Map<Long, BigDecimal> openingFloats) {
        if (openingFloats.isEmpty()) {
            return Map.of();
        }
        var ids = openingFloats.keySet();
        var cashSales = sums(orderPaymentRepository.sumAmountByShiftIdsAndMethod(ids, PaymentMethod.CASH));
        var cashRefunds = sums(orderAdjustmentRepository.sumAmountByShiftIdsAndRefundMethod(ids, RefundMethod.CASH));
        var orderCounts = new HashMap<Long, Long>();
        for (var row : orderRepository.countByShiftIds(ids)) {
            orderCounts.put((Long) row[0], (Long) row[1]);
        }

        var result = new HashMap<Long, CashBreakdown>();
        openingFloats.forEach((shiftId, openingFloat) -> {
            var sales = cashSales.getOrDefault(shiftId, BigDecimal.ZERO);
            var refunds = cashRefunds.getOrDefault(shiftId, BigDecimal.ZERO);
            result.put(shiftId, new CashBreakdown(openingFloat, sales, refunds, openingFloat.add(sales).subtract(refunds),
                    orderCounts.getOrDefault(shiftId, 0L)));
        });
        return result;
    }

    private static Map<Long, BigDecimal> sums(List<Object[]> rows) {
        var sums = new HashMap<Long, BigDecimal>();
        for (var row : rows) {
            sums.put((Long) row[0], (BigDecimal) row[1]);
        }
        return sums;
    }

    public record CashBreakdown(BigDecimal openingFloat, BigDecimal cashSales, BigDecimal cashRefunds,
                                BigDecimal expectedCash, long orderCount) {
    }
}
