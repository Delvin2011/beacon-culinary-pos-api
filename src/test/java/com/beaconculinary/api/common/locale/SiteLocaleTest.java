package com.beaconculinary.api.common.locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiteLocaleTest {
    private static final SiteLocale DEFAULT = SiteLocale.from(new SiteLocaleProperties());

    @Test
    void defaults_areTodaysValues() {
        assertThat(DEFAULT.zone()).isEqualTo(ZoneId.of("Africa/Johannesburg"));
        assertThat(DEFAULT.currencyCode()).isEqualTo("ZAR");
        assertThat(DEFAULT.currencySymbol()).isEqualTo("R");
    }

    @Test
    void businessDate_flipsAtJohannesburgMidnight() {
        assertThat(DEFAULT.businessDate(Instant.parse("2026-11-18T21:59:59Z"))).isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(DEFAULT.businessDate(Instant.parse("2026-11-18T22:00:00Z"))).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(DEFAULT.startOfDay(LocalDate.of(2026, 11, 19))).isEqualTo(Instant.parse("2026-11-18T22:00:00Z"));
        assertThat(DEFAULT.localDateTime(Instant.parse("2026-10-06T11:00:00Z"))).isEqualTo(LocalDateTime.of(2026, 10, 6, 13, 0));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "null        | R 0,00",
            "0.005       | R 0,01",
            "1234.56     | R 1 234,56",
            "1234567.891 | R 1 234 567,89",
            "-0.004      | R 0,00",
            "-10         | -R 10,00",
    })
    void formatMoney(String value, String expected) {
        assertThat(DEFAULT.formatMoney(value == null ? null : new BigDecimal(value))).isEqualTo(expected);
    }

    @Test
    void formatMoney_usesTheSymbolSetting() {
        assertThat(SiteLocale.of("Africa/Johannesburg", "ZAR", "ZAR").formatMoney(new BigDecimal("-1500"))).isEqualTo("-ZAR 1 500,00");
    }

    @Test
    void valuesAreTrimmed() {
        var locale = SiteLocale.of(" Africa/Johannesburg ", " ZAR ", " R ");
        assertThat(locale.zone().getId()).isEqualTo("Africa/Johannesburg");
        assertThat(locale.currencyCode()).isEqualTo("ZAR");
        assertThat(locale.currencySymbol()).isEqualTo("R");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "Mars/Olympus", "Africa/Joburg", "+25:00"})
    void invalidZone_isRefused(String zone) {
        assertThatThrownBy(() -> SiteLocale.of(zone, "ZAR", "R"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("site.time-zone");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"zar", "RAND", "R", "XYZ", "ZA1"})
    void invalidCurrencyCode_isRefused(String code) {
        assertThatThrownBy(() -> SiteLocale.of("Africa/Johannesburg", code, "R"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("site.currency-code");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void blankSymbol_isRefused(String symbol) {
        assertThatThrownBy(() -> SiteLocale.of("Africa/Johannesburg", "ZAR", symbol))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("site.currency-symbol");
    }
}
