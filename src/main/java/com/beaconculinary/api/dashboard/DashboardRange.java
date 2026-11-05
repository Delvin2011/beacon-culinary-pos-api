package com.beaconculinary.api.dashboard;

import java.util.Locale;

/**
 * range=today|7d|month (default 7d). For a period figure, today is today, 7d the last 7 trading
 * days and month the calendar month to date. For a series, today and 7d both mean the last 7
 * trading days.
 */
public enum DashboardRange {
    TODAY("today"),
    SEVEN_DAYS("7d"),
    MONTH("month");

    private final String value;

    DashboardRange(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static DashboardRange parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SEVEN_DAYS;
        }
        var trimmed = raw.trim().toLowerCase(Locale.ROOT);
        for (var range : values()) {
            if (range.value.equals(trimmed)) {
                return range;
            }
        }
        throw DashboardParamException.of("range", "Range must be today, 7d or month.");
    }
}
