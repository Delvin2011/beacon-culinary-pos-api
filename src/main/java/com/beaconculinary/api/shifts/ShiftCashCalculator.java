package com.beaconculinary.api.shifts;

import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderPaymentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.PaymentMethod;
import com.beaconculinary.api.orders.RefundMethod;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

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

    public record CashBreakdown(BigDecimal openingFloat, BigDecimal cashSales, BigDecimal cashRefunds,
                                BigDecimal expectedCash, long orderCount) {
    }
}
