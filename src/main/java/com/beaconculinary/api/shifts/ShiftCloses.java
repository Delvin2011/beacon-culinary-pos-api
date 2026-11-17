package com.beaconculinary.api.shifts;

import com.beaconculinary.api.users.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The close snapshot, shared by a cashier's close (ShiftService) and an admin's force close (O2):
 * expected cash and its two parts as computed by ShiftCashCalculator, the counted cash and variance
 * (both null when the drawer wasn't counted), who closed it and when (UTC).
 */
final class ShiftCloses {
    private ShiftCloses() {
    }

    static void record(Shift shift, ShiftCashCalculator.CashBreakdown breakdown, BigDecimal countedCash, BigDecimal variance,
                       User closedBy, LocalDateTime closedAtUtc) {
        shift.setClosingCash(countedCash);
        shift.setExpectedCash(breakdown.expectedCash());
        shift.setCashSalesAtClose(breakdown.cashSales());
        shift.setCashRefundsAtClose(breakdown.cashRefunds());
        shift.setVariance(variance);
        shift.setStatus(ShiftStatus.CLOSED);
        shift.setClosedBy(closedBy);
        shift.setClosedAt(closedAtUtc);
    }
}
