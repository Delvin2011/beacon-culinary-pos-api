package com.beaconculinary.api.tenancy.jobs;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * T1-6: when a daily job is due for one customer (docs/2026-11-24-routing-data-source.md). Pure: no clock, no state.
 *
 * <ul>
 *   <li>Due at the job's local time in the customer's zone, plus the customer's offset: 0 to {@value #SPREAD_MINUTES}
 *       minutes, from its slug and always the same, so customers in one zone don't all start in the same minute.</li>
 *   <li>Once per local date: a run is due when that date hasn't been run yet.</li>
 *   <li>Daylight saving: a local time that doesn't exist that day (clocks go forward) is moved forward by the gap, so the
 *       day still runs; one that occurs twice (clocks go back) is its first occurrence, and the date is then run, so it
 *       never runs twice.</li>
 * </ul>
 */
public final class DailySchedule {
    /** The spread window: a customer's offset is 0 to this many minutes. */
    public static final int SPREAD_MINUTES = 20;
    /** The latest time a daily job may be set to: with the spread, a run stays on its own local date. */
    public static final LocalTime LATEST_TIME = LocalTime.of(23, 59).minusMinutes(SPREAD_MINUTES);

    private DailySchedule() {
    }

    /** The customer's offset in minutes, 0 to {@value #SPREAD_MINUTES}: from the slug (String.hashCode is fixed by Java). */
    public static int offsetMinutes(String slug) {
        return Math.floorMod(slug.hashCode(), SPREAD_MINUTES + 1);
    }

    /** The instant the job is due on this local date in this zone, offset included. */
    public static Instant dueAt(LocalDate date, LocalTime time, ZoneId zone, int offsetMinutes) {
        return ZonedDateTime.of(date, time, zone).plusMinutes(offsetMinutes).toInstant();
    }

    /** The local date to run for now, or null: today's time hasn't come yet, or today (or later) has been run. */
    public static LocalDate dueDate(Instant now, LocalTime time, ZoneId zone, int offsetMinutes, LocalDate lastRun) {
        var today = LocalDate.ofInstant(now, zone);
        if (lastRun != null && !lastRun.isBefore(today)) {
            return null;
        }
        return now.isBefore(dueAt(today, time, zone, offsetMinutes)) ? null : today;
    }

    /** Refuses a time later than {@link #LATEST_TIME} (or none). */
    public static void check(LocalTime time) {
        if (time == null || time.isAfter(LATEST_TIME)) {
            throw new IllegalArgumentException("A daily job's time must be " + LATEST_TIME + " or earlier (the spread adds up to "
                    + SPREAD_MINUTES + " minutes), not " + time);
        }
    }
}
