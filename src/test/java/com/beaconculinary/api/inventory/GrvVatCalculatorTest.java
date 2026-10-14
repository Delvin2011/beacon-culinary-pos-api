package com.beaconculinary.api.inventory;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** GRV VAT handling §2: the spec's five worked examples (standard rate 15%). */
class GrvVatCalculatorTest {
    private static final BigDecimal STANDARD = new BigDecimal("0.15");

    @ParameterizedTest(name = "{0} {1}: {2} x {3}")
    @CsvSource({
            // basis,    rate,  qty, price,  lineExcl, vat,    lineIncl, unitIncl, unitExcl
            "EXCLUSIVE, 0.15, 10, 100.00, 1000.00,  150.00, 1150.00,  115.0000, 100.0000",
            "INCLUSIVE, 0.15, 10, 115.00, 1000.00,  150.00, 1150.00,  115.0000, 100.0000",
            "EXCLUSIVE, 0,    10, 50.00,  500.00,   0.00,   500.00,   50.0000,  50.0000",
            "INCLUSIVE, 0.15, 50, 18.00,  782.61,   117.39, 900.00,   18.0000,  15.6522",
            "EXCLUSIVE, 0.15, 3,  10.01,  30.03,    4.50,   34.53,    11.5100,  10.0100",
    })
    void workedExamples(String basis, String rate, String qty, String price, String lineExcl, String vat,
                        String lineIncl, String unitIncl, String unitExcl) {
        var amounts = GrvVatCalculator.compute(new BigDecimal(qty), new BigDecimal(price),
                basis.equals("INCLUSIVE"), new BigDecimal(rate));

        assertThat(amounts.lineExclVat()).isEqualByComparingTo(lineExcl);
        assertThat(amounts.lineVat()).isEqualByComparingTo(vat);
        assertThat(amounts.lineInclVat()).isEqualByComparingTo(lineIncl);
        assertThat(amounts.unitCostInclVat()).isEqualTo(new BigDecimal(unitIncl));
        assertThat(amounts.unitCostExclVat()).isEqualTo(new BigDecimal(unitExcl));
        // Line amounts always foot: excl + VAT = incl.
        assertThat(amounts.lineExclVat().add(amounts.lineVat())).isEqualByComparingTo(amounts.lineInclVat());
    }

    @Test
    void samePurchaseWrittenBothWays_givesIdenticalLedgerCostAndStockValue() {
        var exclusive = GrvVatCalculator.compute(new BigDecimal("10"), new BigDecimal("100.00"), false, STANDARD);
        var inclusive = GrvVatCalculator.compute(new BigDecimal("10"), new BigDecimal("115.00"), true, STANDARD);

        assertThat(exclusive.unitCostInclVat()).isEqualTo(inclusive.unitCostInclVat());
        assertThat(exclusive.lineInclVat()).isEqualTo(inclusive.lineInclVat());
        assertThat(exclusive.lineExclVat()).isEqualTo(inclusive.lineExclVat());
    }

    @Test
    void fractionalQuantity_roundsLinesTo2dpAndUnitCostsTo4dp() {
        // 2.5 kg at R39.99 incl: line 99.98 (99.975 half-up), excl 86.94, unit excl 34.7760
        var amounts = GrvVatCalculator.compute(new BigDecimal("2.5"), new BigDecimal("39.99"), true, STANDARD);
        assertThat(amounts.lineInclVat()).isEqualByComparingTo("99.98");
        assertThat(amounts.lineExclVat()).isEqualByComparingTo("86.94");
        assertThat(amounts.lineVat()).isEqualByComparingTo("13.04");
        assertThat(amounts.unitCostExclVat()).isEqualTo(new BigDecimal("34.7760"));
        assertThat(amounts.unitCostInclVat()).isEqualTo(new BigDecimal("39.9900"));
    }
}
