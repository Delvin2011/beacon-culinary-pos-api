package com.beaconculinary.api.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * GRV VAT handling §2: derives both VAT bases for one GRV line from the invoice as written.
 * Line amounts are 2 dp, unit costs 4 dp, all half-up. The ledger cost is always the
 * VAT-inclusive unit cost, so the same purchase written inclusive or exclusive gives the same
 * stock value.
 *
 * <ul>
 *   <li>Inclusive invoice: line incl = qty × price; line excl = line incl ÷ (1 + rate);
 *       unit incl = price; unit excl = line excl ÷ qty.</li>
 *   <li>Exclusive invoice: line excl = qty × price; VAT = line excl × rate;
 *       unit excl = price; unit incl = line incl ÷ qty.</li>
 * </ul>
 */
public final class GrvVatCalculator {
    private static final int MONEY_SCALE = 2;
    private static final int COST_SCALE = 4;

    private GrvVatCalculator() {
    }

    public record LineAmounts(BigDecimal unitCostExclVat, BigDecimal unitCostInclVat,
                              BigDecimal lineExclVat, BigDecimal lineVat, BigDecimal lineInclVat) {
    }

    public static LineAmounts compute(BigDecimal quantity, BigDecimal unitPrice, boolean pricesIncludeVat, BigDecimal rate) {
        var gross = quantity.multiply(unitPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (pricesIncludeVat) {
            var lineExcl = gross.divide(BigDecimal.ONE.add(rate), MONEY_SCALE, RoundingMode.HALF_UP);
            return new LineAmounts(
                    lineExcl.divide(quantity, COST_SCALE, RoundingMode.HALF_UP),
                    unitPrice.setScale(COST_SCALE, RoundingMode.HALF_UP),
                    lineExcl,
                    gross.subtract(lineExcl),
                    gross);
        }
        var lineVat = gross.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        var lineIncl = gross.add(lineVat);
        return new LineAmounts(
                unitPrice.setScale(COST_SCALE, RoundingMode.HALF_UP),
                lineIncl.divide(quantity, COST_SCALE, RoundingMode.HALF_UP),
                gross,
                lineVat,
                lineIncl);
    }
}
