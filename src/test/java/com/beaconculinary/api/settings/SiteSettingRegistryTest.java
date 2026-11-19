package com.beaconculinary.api.settings;

import com.beaconculinary.api.offline.OfflineProperties;
import com.beaconculinary.api.tills.TillProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T0-4a: the registry, value types, single-value limits, cross-field rules and the startup check of the defaults. */
class SiteSettingRegistryTest {
    private final ObjectMapper json = new ObjectMapper();

    // ---- Registry ------------------------------------------------------------------------------

    @Test
    void fifteenSettingsWithUniqueKeysAndTheAgreedSensitiveOnes() {
        assertThat(SiteSetting.values()).hasSize(15);
        assertThat(Arrays.stream(SiteSetting.values()).map(SiteSetting::key).distinct()).hasSize(15);
        assertThat(Arrays.stream(SiteSetting.values()).filter(SiteSetting::sensitive)).containsExactly(
                SiteSetting.VAT_STANDARD_RATE, SiteSetting.FIRST_TRADING_DAY, SiteSetting.TILLS_REQUIRE_REGISTERED,
                SiteSetting.OFFLINE_ACCOUNTS_ENABLED, SiteSetting.OFFLINE_ACCOUNT_CAP);
        assertThat(SiteSetting.byKey("vat.standard-rate")).contains(SiteSetting.VAT_STANDARD_RATE);
        assertThat(SiteSetting.byKey("vat.standrad-rate")).isEmpty();
        assertThat(SiteSetting.byKey(null)).isEmpty();
    }

    // ---- Types ---------------------------------------------------------------------------------

    @Test
    void storedTextRoundTrips() {
        assertThat(SettingType.DECIMAL.toText(SettingType.DECIMAL.fromText("150.00"))).isEqualTo("150.00");
        assertThat(SettingType.INTEGER.fromText(" 30 ")).isEqualTo(30);
        assertThat(SettingType.BOOLEAN.fromText("TRUE")).isEqualTo(true);
        assertThat(SettingType.DATE.fromText("2026-10-12")).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(SettingType.TIME.toText(SettingType.TIME.fromText("12:30"))).isEqualTo("12:30");
        assertThat(SettingType.TIME_RANGE.toText(SettingType.TIME_RANGE.fromText("06:00–15:00"))).isEqualTo("06:00-15:00");
        assertThatThrownBy(() -> SettingType.BOOLEAN.fromText("yes")).isInstanceOf(SettingType.BadValueException.class);
        assertThatThrownBy(() -> SettingType.INTEGER.fromText("1.5")).isInstanceOf(SettingType.BadValueException.class);
    }

    @Test
    void jsonIsStrict() throws Exception {
        assertThat(SettingType.DECIMAL.fromJson(json.readTree("0.15"))).isEqualTo(new BigDecimal("0.15"));
        assertThat(SettingType.INTEGER.fromJson(json.readTree("45"))).isEqualTo(45);
        assertThat(SettingType.TIME.fromJson(json.readTree("\"12:30\""))).isEqualTo(LocalTime.of(12, 30));
        assertThat(SettingType.TIME_RANGE.fromJson(json.readTree("\"07:00-16:00\"")))
                .isEqualTo(new TimeRange(LocalTime.of(7, 0), LocalTime.of(16, 0)));
        for (var bad : new String[][]{{"DECIMAL", "\"0.15\""}, {"INTEGER", "4.5"}, {"INTEGER", "\"4\""},
                {"BOOLEAN", "\"true\""}, {"DATE", "20261012"}, {"DATE", "\"12/10/2026\""}, {"TIME", "\"12:30:00\""},
                {"TIME", "\"9:00\""}, {"TIME_RANGE", "\"06:00\""}}) {
            var type = SettingType.valueOf(bad[0]);
            assertThatThrownBy(() -> type.fromJson(json.readTree(bad[1])))
                    .as(bad[0] + " " + bad[1]).isInstanceOf(SettingType.BadValueException.class)
                    .hasMessage(type.formMessage());
        }
        assertThat(SettingType.DECIMAL.toJson(new BigDecimal("0.15"))).isEqualTo(new BigDecimal("0.15"));
        assertThat(SettingType.TIME.toJson(LocalTime.of(12, 30))).isEqualTo("12:30");
        assertThat(SettingType.DATE.toJson(null)).isNull();
    }

    // ---- Limits --------------------------------------------------------------------------------

    @Test
    void vatRateBetweenZeroAndOneWithAtMostFourDecimals() {
        assertThat(check(SiteSetting.VAT_STANDARD_RATE, "0.15")).isNull();
        assertThat(check(SiteSetting.VAT_STANDARD_RATE, "0")).isNull();
        assertThat(check(SiteSetting.VAT_STANDARD_RATE, "0.9999")).isNull();
        assertThat(check(SiteSetting.VAT_STANDARD_RATE, "0.15000")).isNull();
        assertThat(check(SiteSetting.VAT_STANDARD_RATE, "1")).isEqualTo("Must be between 0 and 0.9999 with at most 4 decimals.");
        assertThat(check(SiteSetting.VAT_STANDARD_RATE, "-0.01")).isNotNull();
        assertThat(check(SiteSetting.VAT_STANDARD_RATE, "0.12345")).isNotNull();
    }

    @Test
    void accountCapAndVarianceAreMoneyWithTwoDecimals() {
        assertThat(check(SiteSetting.OFFLINE_ACCOUNT_CAP, "0")).isNull();
        assertThat(check(SiteSetting.OFFLINE_ACCOUNT_CAP, "150.5")).isNull();
        assertThat(check(SiteSetting.OFFLINE_ACCOUNT_CAP, "150.555")).isEqualTo("Must be between 0 and 99999999.99 with at most 2 decimals.");
        assertThat(check(SiteSetting.OFFLINE_ACCOUNT_CAP, "-1")).isNotNull();
        assertThat(check(SiteSetting.DASHBOARD_VARIANCE_THRESHOLD, "-0.01")).isNotNull();
        assertThat(SiteSettingRules.normalize(SiteSetting.OFFLINE_ACCOUNT_CAP, new BigDecimal("150.5"))).hasToString("150.50");
        assertThat(SiteSettingRules.normalize(SiteSetting.VAT_STANDARD_RATE, new BigDecimal("0.1500"))).hasToString("0.15");
        assertThat(SiteSettingRules.normalize(SiteSetting.DASHBOARD_VOID_REFUND_WATCH_PERCENT, new BigDecimal("100.00"))).hasToString("100");
    }

    @Test
    void integersAndPercents() {
        assertThat(SiteSettingRules.checkLimits(SiteSetting.BOARD_DONE_DISPLAY_MINUTES, 0, 18)).isNull();
        assertThat(SiteSettingRules.checkLimits(SiteSetting.BOARD_DONE_DISPLAY_MINUTES, 1441, 18))
                .isEqualTo("Must be a whole number between 0 and 1440.");
        assertThat(SiteSettingRules.checkLimits(SiteSetting.SESSION_IDLE_LOCK_MINUTES, 0, 18)).isNotNull();
        assertThat(check(SiteSetting.DASHBOARD_SLOW_MEAL_THRESHOLD_PERCENT, "100.01")).isNotNull();
        assertThat(check(SiteSetting.DASHBOARD_SLOW_MEAL_THRESHOLD_PERCENT, "25")).isNull();
    }

    @Test
    void maxOfflineHoursIsCappedByThePlatformGrantLifetime() {
        assertThat(SiteSettingRules.checkLimits(SiteSetting.OFFLINE_MAX_OFFLINE_HOURS, 18, 18)).isNull();
        assertThat(SiteSettingRules.checkLimits(SiteSetting.OFFLINE_MAX_OFFLINE_HOURS, 19, 18))
                .isEqualTo("Must be a whole number between 1 and 18.");
        assertThat(SiteSettingRules.limits(SiteSetting.OFFLINE_MAX_OFFLINE_HOURS, 12).max()).isEqualByComparingTo("12");
        assertThat(SiteSettingRules.checkLimits(SiteSetting.OFFLINE_WARN_OFFLINE_HOURS, 0, 18))
                .isEqualTo("Must be a whole number at least 1.");
    }

    @Test
    void nullOnlyForTheFirstTradingDayAndOpeningHoursInOrder() {
        assertThat(SiteSettingRules.checkLimits(SiteSetting.FIRST_TRADING_DAY, null, 18)).isNull();
        assertThat(SiteSettingRules.checkLimits(SiteSetting.VAT_STANDARD_RATE, null, 18)).isEqualTo("Is required.");
        assertThat(SiteSettingRules.checkLimits(SiteSetting.DASHBOARD_OPENING_HOURS,
                new TimeRange(LocalTime.of(15, 0), LocalTime.of(6, 0)), 18)).isEqualTo("The start must be before the end.");
    }

    @Test
    void crossRulesNameBothSettings() {
        var values = new SiteSettingDefaults(new TillProperties(), offline(), "0.15", "", "10", "50.00", "3.0", "25",
                "12:30", "06:00-15:00").all();
        assertThat(SiteSettingRules.crossRules(values)).isEmpty();

        values.put(SiteSetting.SESSION_IDLE_LOCK_MINUTES, 40);
        values.put(SiteSetting.OFFLINE_WARN_OFFLINE_HOURS, 6);
        var broken = SiteSettingRules.crossRules(values);
        assertThat(broken).containsOnlyKeys(SiteSetting.SESSION_IDLE_LOCK_MINUTES, SiteSetting.SESSION_IDLE_EXPIRY_MINUTES,
                SiteSetting.OFFLINE_WARN_OFFLINE_HOURS, SiteSetting.OFFLINE_MAX_OFFLINE_HOURS);
        assertThat(broken.get(SiteSetting.SESSION_IDLE_EXPIRY_MINUTES)).isEqualTo("Must be at least the idle lock time (40).");
        assertThat(broken.get(SiteSetting.OFFLINE_WARN_OFFLINE_HOURS)).isEqualTo("Must be less than the maximum offline hours (6).");

        values.put(SiteSetting.SESSION_IDLE_EXPIRY_MINUTES, 40);
        values.put(SiteSetting.OFFLINE_WARN_OFFLINE_HOURS, 5);
        assertThat(SiteSettingRules.crossRules(values)).isEmpty();
    }

    // ---- Defaults ------------------------------------------------------------------------------

    @Test
    void defaultsAreTheConfigurationValues() {
        var defaults = new SiteSettingDefaults(new TillProperties(), offline(), "0.15", "2026-10-12", "10", "50.00", "3.0",
                "25", "12:30", "06:00-15:00");
        assertThat(defaults.get(SiteSetting.VAT_STANDARD_RATE)).isEqualTo(new BigDecimal("0.15"));
        assertThat(defaults.get(SiteSetting.FIRST_TRADING_DAY)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(defaults.get(SiteSetting.BOARD_DONE_DISPLAY_MINUTES)).isEqualTo(10);
        assertThat(defaults.get(SiteSetting.DASHBOARD_OPENING_HOURS)).isEqualTo(new TimeRange(LocalTime.of(6, 0), LocalTime.of(15, 0)));
        assertThat(defaults.get(SiteSetting.TILLS_REQUIRE_REGISTERED)).isEqualTo(false);
        assertThat(defaults.get(SiteSetting.SESSION_IDLE_LOCK_MINUTES)).isEqualTo(5);
        assertThat(defaults.get(SiteSetting.SESSION_IDLE_EXPIRY_MINUTES)).isEqualTo(30);
        assertThat(defaults.get(SiteSetting.OFFLINE_MAX_OFFLINE_HOURS)).isEqualTo(6);
        assertThat(defaults.get(SiteSetting.OFFLINE_WARN_OFFLINE_HOURS)).isEqualTo(5);
        assertThat(defaults.get(SiteSetting.OFFLINE_ACCOUNTS_ENABLED)).isEqualTo(true);
        assertThat(defaults.get(SiteSetting.OFFLINE_ACCOUNT_CAP)).isEqualTo(new BigDecimal("150.00"));
        assertThat(defaults.grantMaxHours()).isEqualTo(18);
        assertThat(defaults.all()).hasSize(15);
    }

    @Test
    void anEmptyFirstTradingDayIsNull() {
        var defaults = new SiteSettingDefaults(new TillProperties(), offline(), "0.15", " ", "10", "50.00", "3.0", "25",
                "12:30", "06:00-15:00");
        assertThat(defaults.get(SiteSetting.FIRST_TRADING_DAY)).isNull();
        assertThat(defaults.all()).containsKey(SiteSetting.FIRST_TRADING_DAY);
    }

    @Test
    void anInvalidDefaultStopsStartupNamingTheProperty() {
        assertThatThrownBy(() -> new SiteSettingDefaults(new TillProperties(), offline(), "1.5", "", "10", "50.00", "3.0",
                "25", "12:30", "06:00-15:00"))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("vat.standard-rate '1.5' is not valid");
        assertThatThrownBy(() -> new SiteSettingDefaults(new TillProperties(), offline(), "0.15", "", "10", "50.00", "3.0",
                "25", "12:30", "06:00"))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("dashboard.openingHours '06:00' is not valid");
        assertThatThrownBy(() -> new SiteSettingDefaults(new TillProperties(), offline(), "0.15", "", "-1", "50.00", "3.0",
                "25", "12:30", "06:00-15:00"))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("board.done-display-minutes '-1' is not valid");
        assertThatThrownBy(() -> new SiteSettingDefaults(new TillProperties(), offline(), "0.15", "12 Oct", "10", "50.00",
                "3.0", "25", "12:30", "06:00-15:00"))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("reports.close-day.first-trading-day");
    }

    static OfflineProperties offline() {
        return new OfflineProperties();
    }

    private static String check(SiteSetting setting, String decimal) {
        return SiteSettingRules.checkLimits(setting, new BigDecimal(decimal), 18);
    }
}
