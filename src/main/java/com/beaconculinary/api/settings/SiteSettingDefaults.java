package com.beaconculinary.api.settings;

import com.beaconculinary.api.offline.OfflineProperties;
import com.beaconculinary.api.tills.TillProperties;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** The @Value defaults, parsed once. */
    private final Map<SiteSetting, Object> values = new EnumMap<>(SiteSetting.class);
    private final TillProperties tillProperties;
    private final OfflineProperties offlineProperties;

    /** T0-5c: the default for stock.locations.max-active when it isn't configured. */
    static final String DEFAULT_STOCK_LOCATIONS_MAX_ACTIVE = "10";

    /** T0-6a: the default for auth.invites.expiry-hours when it isn't configured. */
    static final String DEFAULT_INVITE_EXPIRY_HOURS = "72";

    /** As before T0-5c, with stock.locations.max-active and auth.invites.expiry-hours at their defaults (kept for tests). */
    public SiteSettingDefaults(TillProperties tillProperties, OfflineProperties offlineProperties,
                               String vatStandardRate, String firstTradingDay, String boardDoneDisplayMinutes,
                               String varianceThreshold, String voidRefundWatchPercent, String slowMealThresholdPercent,
                               String slowMealAfter, String openingHours) {
        this(tillProperties, offlineProperties, vatStandardRate, firstTradingDay, boardDoneDisplayMinutes,
                varianceThreshold, voidRefundWatchPercent, slowMealThresholdPercent, slowMealAfter, openingHours,
                DEFAULT_STOCK_LOCATIONS_MAX_ACTIVE, DEFAULT_INVITE_EXPIRY_HOURS);
    }

    @Autowired
    public SiteSettingDefaults(TillProperties tillProperties, OfflineProperties offlineProperties,
                               @Value("${vat.standard-rate:0.15}") String vatStandardRate,
                               @Value("${reports.close-day.first-trading-day:}") String firstTradingDay,
                               @Value("${board.done-display-minutes:10}") String boardDoneDisplayMinutes,
                               @Value("${dashboard.variance.threshold:50.00}") String varianceThreshold,
                               @Value("${dashboard.voidRefund.watchPercent:3.0}") String voidRefundWatchPercent,
                               @Value("${dashboard.slowMeal.thresholdPercent:25}") String slowMealThresholdPercent,
                               @Value("${dashboard.slowMeal.afterTime:12:30}") String slowMealAfter,
                               @Value("${dashboard.openingHours:06:00-15:00}") String openingHours,
                               @Value("${stock.locations.max-active:" + DEFAULT_STOCK_LOCATIONS_MAX_ACTIVE + "}")
                               String stockLocationsMaxActive,
                               @Value("${auth.invites.expiry-hours:" + DEFAULT_INVITE_EXPIRY_HOURS + "}")
                               String inviteExpiryHours) {
        this.tillProperties = tillProperties;
        this.offlineProperties = offlineProperties;
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
        parse(SiteSetting.STOCK_LOCATIONS_MAX_ACTIVE, stockLocationsMaxActive);
        parse(SiteSetting.AUTH_INVITE_EXPIRY_HOURS, inviteExpiryHours);
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
            var problem = SiteSettingRules.checkLimits(setting, get(setting), grantMaxHours());
            if (problem != null) {
                throw new IllegalStateException(setting.key() + " '" + get(setting) + "' is not valid: " + problem);
            }
        }
        var broken = SiteSettingRules.crossRules(all());
        if (!broken.isEmpty()) {
            var first = broken.entrySet().iterator().next();
            throw new IllegalStateException(first.getKey().key() + " is not valid: " + first.getValue());
        }
    }

    /**
     * The default of a setting (null only for an empty first trading day). The pos.* defaults are
     * read from the bound property beans on each call, as their consumers did before T0-4a.
     */
    public Object get(SiteSetting setting) {
        return switch (setting) {
            case TILLS_REQUIRE_REGISTERED -> tillProperties.getTills().isRequireRegistered();
            case SESSION_IDLE_LOCK_MINUTES -> tillProperties.getSession().getIdleLockMinutes();
            case SESSION_IDLE_EXPIRY_MINUTES -> tillProperties.getSession().getIdleExpiryMinutes();
            case OFFLINE_MAX_OFFLINE_HOURS -> offlineProperties.getMaxOfflineHours();
            case OFFLINE_WARN_OFFLINE_HOURS -> offlineProperties.getWarnOfflineHours();
            case OFFLINE_ACCOUNTS_ENABLED -> offlineProperties.isAccountsEnabled();
            case OFFLINE_ACCOUNT_CAP -> offlineProperties.getAccountCap();
            default -> values.get(setting);
        };
    }

    /** All defaults (a copy). */
    public Map<SiteSetting, Object> all() {
        var all = new EnumMap<SiteSetting, Object>(SiteSetting.class);
        for (var setting : SiteSetting.values()) {
            all.put(setting, get(setting));
        }
        return all;
    }

    /** Platform: pos.offline.grant-max-hours, the upper limit for max-offline-hours. */
    public int grantMaxHours() {
        return offlineProperties.getGrantMaxHours();
    }
}
