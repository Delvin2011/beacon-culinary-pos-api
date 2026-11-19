package com.beaconculinary.api.locale;

import com.beaconculinary.api.support.TestLocale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** T0-2: the money text as DashboardFormat.rand wrote it before SiteLocale ("R 1 234,56"); it now
 * comes from SiteLocale.formatMoney and must not change. */
class MoneyFormatGoldenTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "null          | R 0,00",
            "0             | R 0,00",
            "0.004         | R 0,00",
            "0.005         | R 0,01",
            "1             | R 1,00",
            "45            | R 45,00",
            "999.99        | R 999,99",
            "1000          | R 1 000,00",
            "1234.56       | R 1 234,56",
            "1240          | R 1 240,00",
            "100000        | R 100 000,00",
            "1234567.891   | R 1 234 567,89",
            "-0.004        | R 0,00",
            "-10           | -R 10,00",
            "-1234.5       | -R 1 234,50",
    })
    void money(String value, String expected) {
        assertThat(TestLocale.SITE.formatMoney(value == null ? null : new BigDecimal(value))).isEqualTo(expected);
    }
}
