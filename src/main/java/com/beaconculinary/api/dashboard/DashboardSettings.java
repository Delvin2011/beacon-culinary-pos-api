package com.beaconculinary.api.dashboard;

import com.beaconculinary.api.settings.SiteSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Back-office dashboard configuration. Thresholds and opening hours are site settings (T0-4a:
 * GET/PUT /admin/settings; defaults from the {@code dashboard.*} properties), read on every call.
 * Each prerequisite stage has a feature flag (an entitlement, configuration only),
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
    private final SiteSettings siteSettings;
    private final boolean cacheEnabled;

    public DashboardSettings(Environment environment, SiteSettings siteSettings,
                             @Value("${dashboard.cache.enabled:true}") boolean cacheEnabled) {
        this.environment = environment;
        this.siteSettings = siteSettings;
        this.cacheEnabled = cacheEnabled;
    }

    /** Read on every call, so a flag flipped in configuration applies without a code change.
     * Every stage listed has shipped, so each defaults to on. */
    public boolean enabled(Feature feature) {
        return environment.getProperty(feature.property(), Boolean.class, true);
    }

    public BigDecimal varianceThreshold() {
        return siteSettings.dashboardVarianceThreshold();
    }

    public BigDecimal voidRefundWatchPercent() {
        return siteSettings.dashboardVoidRefundWatchPercent();
    }

    public BigDecimal slowMealThresholdPercent() {
        return siteSettings.dashboardSlowMealThresholdPercent();
    }

    public LocalTime slowMealAfter() {
        return siteSettings.dashboardSlowMealAfter();
    }

    public LocalTime openFrom() {
        return siteSettings.dashboardOpeningHours().from();
    }

    public LocalTime openTo() {
        return siteSettings.dashboardOpeningHours().to();
    }

    public boolean cacheEnabled() {
        return cacheEnabled;
    }

    /** The hours of day the hourly charts run across: 06:00-15:00 gives 6..14. */
    public int firstHour() {
        return openFrom().getHour();
    }

    public int lastHour() {
        var openTo = openTo();
        return openTo.getMinute() == 0 && openTo.getSecond() == 0 ? openTo.getHour() - 1 : openTo.getHour();
    }
}
