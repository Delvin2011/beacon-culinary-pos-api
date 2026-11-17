package com.beaconculinary.api.orders;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O2b: the VAT-included rule, with the examples from section 3 of the receipt contract. */
class VatRuleTest {
    private static final BigDecimal STANDARD = new BigDecimal("0.15");

    @ParameterizedTest(name = "{0} at 15% includes {1} VAT")
    @CsvSource({
            "50.00, 6.52",
            "100.00, 13.04",
            "33.33, 4.35",
            "1.15, 0.15",
            "0.01, 0.00",
            "0.00, 0.00",
            "85.00, 11.09",
            "99999.99, 13043.48"
    })
    void standardRate_examplesFromTheContract(String total, String vat) {
        assertThat(VatRule.includedVat(new BigDecimal(total), STANDARD)).isEqualByComparingTo(vat)
                .hasScaleOf(2);
    }

    @Test
    void zeroRate_includesNoVat() {
        assertThat(VatRule.includedVat(new BigDecimal("85.00"), BigDecimal.ZERO)).isEqualByComparingTo("0.00").hasScaleOf(2);
    }

    @Test
    void anExactHalfCent_roundsUp() {
        // At 100%, 0.01 holds 0.005 VAT: exactly half a cent. (At 15% an exact half can't occur.)
        assertThat(VatRule.includedVat(new BigDecimal("0.01"), BigDecimal.ONE)).isEqualByComparingTo("0.01");
        assertThat(VatRule.includedVat(new BigDecimal("0.03"), BigDecimal.ONE)).isEqualByComparingTo("0.02");
    }

    @Test
    void roundsOnce_notPerStep() {
        // 0.10 × 0.15 = 0.015, ÷ 1.15 = 0.0130… → 0.01. Rounding the product first (0.015 → 0.02)
        // and then dividing would give 0.0174… → 0.02.
        assertThat(VatRule.includedVat(new BigDecimal("0.10"), STANDARD)).isEqualByComparingTo("0.01");
    }

    @Test
    void storedRate_hasFourDecimals() {
        assertThat(VatRule.storedRate(STANDARD)).isEqualTo(new BigDecimal("0.1500"));
    }

    @Test
    void negativeOrMissingValues_areRefused() {
        assertThatThrownBy(() -> VatRule.includedVat(new BigDecimal("-1.00"), STANDARD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VatRule.includedVat(BigDecimal.ONE, new BigDecimal("-0.15"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VatRule.includedVat(null, STANDARD)).isInstanceOf(IllegalArgumentException.class);
    }
}
