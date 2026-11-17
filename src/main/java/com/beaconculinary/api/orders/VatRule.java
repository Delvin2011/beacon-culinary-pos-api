package com.beaconculinary.api.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * O2b: the VAT included in a sale's total. Prices include VAT, so this is the part of the total that
 * is VAT, never an amount added on top:
 *
 * <pre>vatIncludedAmount = round(total × rate ÷ (1 + rate), 2 decimals, half up)</pre>
 *
 * Multiplied exactly and divided once, with a single rounding; no per-line VAT. Applied to the
 * order's originalTotal (after a discount at sale, before any void or refund) and stored at
 * creation. The offline till (O4) must apply exactly this rule; see section 3 of
 * docs/2026-11-17-o2b-receipt-data-api-contract.md.
 */
public final class VatRule {
    /** The rate as stored on orders (DECIMAL(6, 4)). */
    public static final int RATE_SCALE = 4;

    private VatRule() {
    }

    public static BigDecimal includedVat(BigDecimal total, BigDecimal rate) {
        if (total == null || rate == null) {
            throw new IllegalArgumentException("total and rate are required");
        }
        if (total.signum() < 0 || rate.signum() < 0) {
            throw new IllegalArgumentException("total and rate can't be negative");
        }
        return total.multiply(rate).divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
    }

    /** The rate as stored with an order, e.g. 0.15 becomes 0.1500. */
    public static BigDecimal storedRate(BigDecimal rate) {
        return rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }
}
