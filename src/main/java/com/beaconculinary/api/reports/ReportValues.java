package com.beaconculinary.api.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money is always a 2-decimal BigDecimal, rounded half-up. */
public final class ReportValues {
    private ReportValues() {
    }

    public static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /** a / b as money; 0.00 when b is zero. */
    public static BigDecimal divide(BigDecimal a, long b) {
        return b == 0 ? money(BigDecimal.ZERO) : money(a).divide(BigDecimal.valueOf(b), 2, RoundingMode.HALF_UP);
    }
}
