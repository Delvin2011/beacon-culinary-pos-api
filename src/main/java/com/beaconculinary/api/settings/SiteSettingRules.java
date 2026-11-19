package com.beaconculinary.api.settings;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * T0-4a: the checks on site setting values (contract section 4.1 and 4.3). Pure: no database.
 * The first trading day's rules need the database and are in FirstTradingDayRule.
 */
public final class SiteSettingRules {
    private SiteSettingRules() {
    }

    /** The limits shown and applied: the static ones, plus grant-max-hours as max-offline-hours' maximum. */
    public static SiteSetting.Limits limits(SiteSetting setting, int grantMaxHours) {
        var limits = setting.staticLimits();
        if (setting == SiteSetting.OFFLINE_MAX_OFFLINE_HOURS) {
            return limits.withMax(BigDecimal.valueOf(grantMaxHours));
        }
        return limits;
    }

    /** null when the value meets the setting's own limits, else the message for the 400 field map. */
    public static String checkLimits(SiteSetting setting, Object value, int grantMaxHours) {
        var limits = limits(setting, grantMaxHours);
        if (value == null) {
            return limits.nullable() ? null : "Is required.";
        }
        if (value instanceof TimeRange range && !range.from().isBefore(range.to())) {
            return "The start must be before the end.";
        }
        var number = value instanceof BigDecimal d ? d : value instanceof Integer i ? BigDecimal.valueOf(i) : null;
        if (number == null) {
            return null;
        }
        var tooPrecise = limits.scale() != null && number.stripTrailingZeros().scale() > limits.scale();
        var tooLow = limits.min() != null && number.compareTo(limits.min()) < 0;
        var tooHigh = limits.max() != null && number.compareTo(limits.max()) > 0;
        if (!tooPrecise && !tooLow && !tooHigh) {
            return null;
        }
        var range = limits.max() == null ? "at least " + limits.min().toPlainString()
                : "between " + limits.min().toPlainString() + " and " + limits.max().toPlainString();
        if (setting.type() == SettingType.INTEGER) {
            return "Must be a whole number " + range + ".";
        }
        return "Must be " + range + " with at most " + limits.scale() + (limits.scale() == 1 ? " decimal." : " decimals.");
    }

    /** The value as stored: money with exactly its decimals (150.00), other decimals without trailing zeros. */
    public static Object normalize(SiteSetting setting, Object value) {
        if (!(value instanceof BigDecimal decimal)) {
            return value;
        }
        var limits = setting.staticLimits();
        if (limits.fixedScale()) {
            return decimal.setScale(limits.scale());
        }
        var stripped = decimal.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** Whether two values of a setting are the same (1.50 equals 1.5). */
    public static boolean sameValue(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    /**
     * The cross-field rules on a full set of values (every setting present). Each broken rule names
     * every setting it involves; the result is empty when all hold.
     */
    public static Map<SiteSetting, String> crossRules(Map<SiteSetting, Object> values) {
        var broken = new LinkedHashMap<SiteSetting, String>();
        var lock = (Integer) values.get(SiteSetting.SESSION_IDLE_LOCK_MINUTES);
        var expiry = (Integer) values.get(SiteSetting.SESSION_IDLE_EXPIRY_MINUTES);
        if (lock != null && expiry != null && expiry < lock) {
            broken.put(SiteSetting.SESSION_IDLE_LOCK_MINUTES, "Must be at most the idle expiry time (" + expiry + ").");
            broken.put(SiteSetting.SESSION_IDLE_EXPIRY_MINUTES, "Must be at least the idle lock time (" + lock + ").");
        }
        var warn = (Integer) values.get(SiteSetting.OFFLINE_WARN_OFFLINE_HOURS);
        var max = (Integer) values.get(SiteSetting.OFFLINE_MAX_OFFLINE_HOURS);
        if (warn != null && max != null && warn >= max) {
            broken.put(SiteSetting.OFFLINE_WARN_OFFLINE_HOURS, "Must be less than the maximum offline hours (" + max + ").");
            broken.put(SiteSetting.OFFLINE_MAX_OFFLINE_HOURS, "Must be more than the warning hours (" + warn + ").");
        }
        return broken;
    }
}
