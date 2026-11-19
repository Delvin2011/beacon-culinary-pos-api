package com.beaconculinary.api.reports;

import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.common.locale.SiteLocale;

import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * An inclusive range of trading days (business-zone dates, see SiteLocale). A trading day is the
 * local date a shift opened on, so the range is applied as a half-open UTC window on
 * shifts.opened_at, which keeps the predicate sargable on idx_shifts_opened_at.
 */
public record ReportRange(LocalDate from, LocalDate to) {
    public Timestamp fromUtc(SiteLocale site) {
        return startOfDayUtc(from, site);
    }

    public Timestamp toUtcExclusive(SiteLocale site) {
        return startOfDayUtc(to.plusDays(1), site);
    }

    /** The stored (UTC) value at which a business day starts. */
    public static Timestamp startOfDayUtc(LocalDate businessDay, SiteLocale site) {
        return Timestamp.valueOf(UtcTimestamps.toUtc(site.startOfDay(businessDay)));
    }
}
