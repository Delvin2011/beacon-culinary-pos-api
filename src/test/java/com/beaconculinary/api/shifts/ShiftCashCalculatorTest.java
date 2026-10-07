package com.beaconculinary.api.shifts;

import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderPaymentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.PaymentMethod;
import com.beaconculinary.api.orders.RefundMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pins the expected-cash formula: opening float + cash payments on orders sold in the shift
 * - cash-method adjustments processed in the shift. Card/account payments and ACCOUNT_BALANCE
 * adjustments never enter it. */
class ShiftCashCalculatorTest {
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderPaymentRepository orderPaymentRepository = mock(OrderPaymentRepository.class);
    private final OrderAdjustmentRepository orderAdjustmentRepository = mock(OrderAdjustmentRepository.class);
    private final ShiftCashCalculator calculator =
            new ShiftCashCalculator(orderRepository, orderPaymentRepository, orderAdjustmentRepository);

    @Test
    void expectedCash_isFloatPlusCashSalesMinusCashAdjustments() {
        when(orderPaymentRepository.sumAmountByShiftIdAndMethod(41L, PaymentMethod.CASH)).thenReturn(new BigDecimal("3720.00"));
        when(orderAdjustmentRepository.sumAmountByShiftIdAndRefundMethod(41L, RefundMethod.CASH)).thenReturn(new BigDecimal("150.00"));
        when(orderRepository.countByShiftId(41L)).thenReturn(74L);

        var breakdown = calculator.compute(41L, new BigDecimal("200.00"));

        assertThat(breakdown.openingFloat()).isEqualByComparingTo("200.00");
        assertThat(breakdown.cashSales()).isEqualByComparingTo("3720.00");
        assertThat(breakdown.cashRefunds()).isEqualByComparingTo("150.00");
        assertThat(breakdown.expectedCash()).isEqualByComparingTo("3770.00");
        assertThat(breakdown.orderCount()).isEqualTo(74L);
        // Only the CASH terms are ever queried.
        verify(orderPaymentRepository).sumAmountByShiftIdAndMethod(41L, PaymentMethod.CASH);
        verify(orderAdjustmentRepository).sumAmountByShiftIdAndRefundMethod(41L, RefundMethod.CASH);
    }

    @Test
    void emptyShift_expectedCashIsTheOpeningFloat() {
        when(orderPaymentRepository.sumAmountByShiftIdAndMethod(7L, PaymentMethod.CASH)).thenReturn(BigDecimal.ZERO);
        when(orderAdjustmentRepository.sumAmountByShiftIdAndRefundMethod(7L, RefundMethod.CASH)).thenReturn(BigDecimal.ZERO);

        assertThat(calculator.compute(7L, new BigDecimal("500.00")).expectedCash()).isEqualByComparingTo("500.00");
    }

    @Test
    void refundsCanExceedSales_givingExpectedCashBelowTheFloat() {
        // A later shift processing a refund for an earlier shift's sale (cross-shift attribution).
        when(orderPaymentRepository.sumAmountByShiftIdAndMethod(9L, PaymentMethod.CASH)).thenReturn(BigDecimal.ZERO);
        when(orderAdjustmentRepository.sumAmountByShiftIdAndRefundMethod(9L, RefundMethod.CASH)).thenReturn(new BigDecimal("50.00"));

        assertThat(calculator.compute(9L, new BigDecimal("200.00")).expectedCash()).isEqualByComparingTo("150.00");
    }
}
