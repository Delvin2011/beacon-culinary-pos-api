package com.beaconculinary.api.dashboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Back-office dashboard configuration (no settings screen yet; no tables). Thresholds and opening
 * hours come from {@code dashboard.*} properties; each prerequisite stage has a feature flag,
 * {@code dashboard.features.<name>}, set when that stage ships. A section whose stage is off is
 * left out of the layout.
 */
@Component
public class DashboardSettings {
    /** One flag per stage the dashboard depends on. */
    public enum Feature {
        COMPLIMENTARY("complimentary"),
        CATEGORIES("categories"),
        BAR_STATION("barStation"),
        LIGHT_MEAL_STOCK("lightMealStock"),
        SIGN_OFF("signOff"),
        REPORT_DEFINITIONS("reportDefinitions"),
        ACCOUNT_REPORTS("accountReports");

        private final String property;

        Feature(String property) {
            this.property = property;
        }

        public String property() {
            return "dashboard.features." + property;
        }
    }

    private final Environment environment;
    private final BigDecimal varianceThreshold;
    private final BigDecimal voidRefundWatchPercent;
    private final BigDecimal slowMealThresholdPercent;
    private final LocalTime slowMealAfter;
    private final LocalTime openFrom;
    private final LocalTime openTo;
    private final boolean cacheEnabled;

    public DashboardSettings(Environment environment,
                             @Value("${dashboard.variance.threshold:50.00}") BigDecimal varianceThreshold,
                             @Value("${dashboard.voidRefund.watchPercent:3.0}") BigDecimal voidRefundWatchPercent,
                             @Value("${dashboard.slowMeal.thresholdPercent:25}") BigDecimal slowMealThresholdPercent,
                             @Value("${dashboard.slowMeal.afterTime:12:30}") String slowMealAfter,
                             @Value("${dashboard.openingHours:06:00-15:00}") String openingHours,
                             @Value("${dashboard.cache.enabled:true}") boolean cacheEnabled) {
        this.environment = environment;
        this.varianceThreshold = varianceThreshold;
        this.voidRefundWatchPercent = voidRefundWatchPercent;
        this.slowMealThresholdPercent = slowMealThresholdPercent;
        this.slowMealAfter = LocalTime.parse(slowMealAfter.trim());
        // "06:00-15:00" (an en dash is accepted too).
        var hours = openingHours.split("[-–]");
        if (hours.length != 2) {
            throw new IllegalStateException("dashboard.openingHours must look like 06:00-15:00, not " + openingHours);
        }
        this.openFrom = LocalTime.parse(hours[0].trim());
        this.openTo = LocalTime.parse(hours[1].trim());
        this.cacheEnabled = cacheEnabled;
    }

    /** Read on every call, so a flag flipped in configuration applies without a code change.
     * Every stage listed has shipped, so each defaults to on. */
    public boolean enabled(Feature feature) {
        return environment.getProperty(feature.property(), Boolean.class, true);
    }

    public BigDecimal varianceThreshold() {
        return varianceThreshold;
    }

    public BigDecimal voidRefundWatchPercent() {
        return voidRefundWatchPercent;
    }

    public BigDecimal slowMealThresholdPercent() {
        return slowMealThresholdPercent;
    }

    public LocalTime slowMealAfter() {
        return slowMealAfter;
    }

    public LocalTime openFrom() {
        return openFrom;
    }

    public LocalTime openTo() {
        return openTo;
    }

    public boolean cacheEnabled() {
        return cacheEnabled;
    }

    /** The hours of day the hourly charts run across: 06:00-15:00 gives 6..14. */
    public int firstHour() {
        return openFrom.getHour();
    }

    public int lastHour() {
        return openTo.getMinute() == 0 && openTo.getSecond() == 0 ? openTo.getHour() - 1 : openTo.getHour();
    }
}
