package com.beaconculinary.api.settings;

import com.beaconculinary.api.offline.OfflineProperties;
import com.beaconculinary.api.tills.TillProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * T0-4a: each site setting's default, i.e. its configuration value (application.yaml / environment),
 * used while the customer has saved nothing. The {@code @Value} properties moved here from
 * VatSettings, CloseDaySettings, PublicBoardSettings and DashboardSettings; the pos.* ones are read
 * from TillProperties and OfflineProperties, whose bean validation still runs at startup.
 *
 * <p>Startup check: every default must meet its setting's limits and the cross-field rules, or the
 * app refuses to start with a message naming the property (as the earlier checks did, e.g.
 * "dashboard.openingHours must look like 06:00-15:00").
 */
@Component
public class SiteSettingDefaults {
    private final Map<SiteSetting, Object> values = new EnumMap<>(SiteSetting.class);
    private final int grantMaxHours;

    public SiteSettingDefaults(TillProperties tillProperties, OfflineProperties offlineProperties,
                               @Value("${vat.standard-rate:0.15}") String vatStandardRate,
                               @Value("${reports.close-day.first-trading-day:}") String firstTradingDay,
                               @Value("${board.done-display-minutes:10}") String boardDoneDisplayMinutes,
                               @Value("${dashboard.variance.threshold:50.00}") String varianceThreshold,
                               @Value("${dashboard.voidRefund.watchPercent:3.0}") String voidRefundWatchPercent,
                               @Value("${dashboard.slowMeal.thresholdPercent:25}") String slowMealThresholdPercent,
                               @Value("${dashboard.slowMeal.afterTime:12:30}") String slowMealAfter,
                               @Value("${dashboard.openingHours:06:00-15:00}") String openingHours) {
        this.grantMaxHours = offlineProperties.getGrantMaxHours();
        parse(SiteSetting.VAT_STANDARD_RATE, vatStandardRate);
        if (firstTradingDay == null || firstTradingDay.isBlank()) {
            values.put(SiteSetting.FIRST_TRADING_DAY, null);
        } else {
            parse(SiteSetting.FIRST_TRADING_DAY, firstTradingDay);
        }
        parse(SiteSetting.BOARD_DONE_DISPLAY_MINUTES, boardDoneDisplayMinutes);
        parse(SiteSetting.DASHBOARD_VARIANCE_THRESHOLD, varianceThreshold);
        parse(SiteSetting.DASHBOARD_VOID_REFUND_WATCH_PERCENT, voidRefundWatchPercent);
        parse(SiteSetting.DASHBOARD_SLOW_MEAL_THRESHOLD_PERCENT, slowMealThresholdPercent);
        parse(SiteSetting.DASHBOARD_SLOW_MEAL_AFTER_TIME, slowMealAfter);
        parse(SiteSetting.DASHBOARD_OPENING_HOURS, openingHours);
        values.put(SiteSetting.TILLS_REQUIRE_REGISTERED, tillProperties.getTills().isRequireRegistered());
        values.put(SiteSetting.SESSION_IDLE_LOCK_MINUTES, tillProperties.getSession().getIdleLockMinutes());
        values.put(SiteSetting.SESSION_IDLE_EXPIRY_MINUTES, tillProperties.getSession().getIdleExpiryMinutes());
        values.put(SiteSetting.OFFLINE_MAX_OFFLINE_HOURS, offlineProperties.getMaxOfflineHours());
        values.put(SiteSetting.OFFLINE_WARN_OFFLINE_HOURS, offlineProperties.getWarnOfflineHours());
        values.put(SiteSetting.OFFLINE_ACCOUNTS_ENABLED, offlineProperties.isAccountsEnabled());
        values.put(SiteSetting.OFFLINE_ACCOUNT_CAP, offlineProperties.getAccountCap());
        check();
    }

    private void parse(SiteSetting setting, String text) {
        try {
            values.put(setting, setting.type().fromText(text));
        } catch (SettingType.BadValueException e) {
            throw new IllegalStateException(setting.key() + " '" + text + "' is not valid: " + e.getMessage());
        }
    }

    private void check() {
        for (var setting : SiteSetting.values()) {
            var problem = SiteSettingRules.checkLimits(setting, values.get(setting), grantMaxHours);
            if (problem != null) {
                throw new IllegalStateException(setting.key() + " '" + values.get(setting) + "' is not valid: " + problem);
            }
        }
        var broken = SiteSettingRules.crossRules(values);
        if (!broken.isEmpty()) {
            var first = broken.entrySet().iterator().next();
            throw new IllegalStateException(first.getKey().key() + " is not valid: " + first.getValue());
        }
    }

    /** The default of a setting (null only for an empty first trading day). */
    public Object get(SiteSetting setting) {
        return values.get(setting);
    }

    /** All defaults (a copy). */
    public Map<SiteSetting, Object> all() {
        return new EnumMap<>(values);
    }

    /** Platform: pos.offline.grant-max-hours, the upper limit for max-offline-hours. */
    public int grantMaxHours() {
        return grantMaxHours;
    }
}
