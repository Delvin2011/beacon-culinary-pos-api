package com.beaconculinary.api.menu;

import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.common.locale.SiteLocale;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * T0-5a: whether a period was open at a past instant, from its values then (meal_period_changes):
 * after the last change at or before it, else before the first change after it, else the current
 * values. Offline sync uses it for PERIOD_CLOSED, so a time change made after a sale doesn't flag it.
 */
@Component
public class MealPeriodHistory {
    private final MealPeriodChangeStore changes;
    private final SiteLocale siteLocale;

    public MealPeriodHistory(MealPeriodChangeStore changes, SiteLocale siteLocale) {
        this.changes = changes;
        this.siteLocale = siteLocale;
    }

    public boolean wasOpenAt(MealPeriod period, Instant at) {
        var local = at.atZone(siteLocale.zone()).toLocalTime();
        return changes.valuesAt(period.getId(), UtcTimestamps.toUtc(at))
                .map(then -> MealPeriod.isOpen(then.active(), period.isAllDay(), then.startTime(), then.endTime(), local))
                .orElseGet(() -> period.isActiveAt(local));
    }
}
