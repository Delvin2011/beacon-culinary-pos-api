package com.beaconculinary.api.reports;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.common.UtcTimestamps;

import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * An inclusive range of Johannesburg trading days. A trading day is the local date a shift
 * opened on, so the range is applied as a half-open UTC window on shifts.opened_at, which keeps
 * the predicate sargable on idx_shifts_opened_at.
 */
public record ReportRange(LocalDate from, LocalDate to) {
    public Timestamp fromUtc() {
        return startOfDayUtc(from);
    }

    public Timestamp toUtcExclusive() {
        return startOfDayUtc(to.plusDays(1));
    }

    private static Timestamp startOfDayUtc(LocalDate businessDay) {
        return Timestamp.valueOf(UtcTimestamps.toUtc(businessDay.atStartOfDay(ClockConfig.BUSINESS_ZONE).toInstant()));
    }
}
