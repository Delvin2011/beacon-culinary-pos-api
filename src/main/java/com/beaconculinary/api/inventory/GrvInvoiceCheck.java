package com.beaconculinary.api.inventory;

import java.math.BigDecimal;

/** GRV VAT handling §3: compares the invoice total as printed with the computed VAT-inclusive
 * total. PASS when they differ by R 0.05 or less, otherwise WARN. Informational only: it never
 * blocks a GRV. */
public final class GrvInvoiceCheck {
    static final BigDecimal TOLERANCE = new BigDecimal("0.05");

    private GrvInvoiceCheck() {
    }

    /** null when no printed total was given. */
    public static GrvDto.InvoiceCheck of(BigDecimal printedTotal, BigDecimal computedTotalInclVat) {
        if (printedTotal == null) {
            return null;
        }
        var difference = printedTotal.subtract(computedTotalInclVat);
        var status = difference.abs().compareTo(TOLERANCE) <= 0 ? GrvDto.InvoiceCheck.Status.PASS : GrvDto.InvoiceCheck.Status.WARN;
        return new GrvDto.InvoiceCheck(status, difference);
    }
}
