package com.beaconculinary.api.dashboard;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.reports.ReportRange;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Trading days (R1): the Johannesburg dates on which at least one shift opened, from
 * v_shift_facts. Everything is "as of now" on the injected clock, so a shift that opens later
 * (or a test fixture's later day) never counts yet.
 */
@Component
@AllArgsConstructor
public class TradingCalendar {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ClockConfig.BUSINESS_ZONE);
    }

    /** The Johannesburg date-time now. */
    public LocalDateTime localNow() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfig.BUSINESS_ZONE);
    }

    /** The n most recent trading days up to and including a day, oldest first. */
    public List<LocalDate> lastTradingDays(LocalDate upTo, int n) {
        var days = new ArrayList<>(jdbc.queryForList("""
                SELECT DISTINCT TOP (:n) trading_day FROM v_shift_facts
                WHERE opened_at_utc < :to AND opened_at_utc <= :now
                ORDER BY trading_day DESC""",
                new MapSqlParameterSource().addValue("n", n).addValue("to", startUtc(upTo.plusDays(1)))
                        .addValue("now", nowUtc()), java.sql.Date.class).stream().map(java.sql.Date::toLocalDate).toList());
        days.sort(Comparator.naturalOrder());
        return days;
    }

    /** Trading days in an inclusive range of dates. */
    public Set<LocalDate> tradingDaysBetween(LocalDate from, LocalDate to) {
        return new TreeSet<>(jdbc.queryForList("""
                SELECT DISTINCT trading_day FROM v_shift_facts
                WHERE opened_at_utc >= :from AND opened_at_utc < :to AND opened_at_utc <= :now""",
                new MapSqlParameterSource().addValue("from", startUtc(from)).addValue("to", startUtc(to.plusDays(1)))
                        .addValue("now", nowUtc()), java.sql.Date.class).stream().map(java.sql.Date::toLocalDate).toList());
    }

    public boolean isTradingDay(LocalDate day) {
        return !tradingDaysBetween(day, day).isEmpty();
    }

    /**
     * The dates a period figure covers: today; the last 7 trading days (from the oldest of them to
     * today, which is exactly their data, as other days have no shifts); or the month to date.
     */
    public ReportRange span(DashboardRange range) {
        var today = today();
        return switch (range) {
            case TODAY -> new ReportRange(today, today);
            case SEVEN_DAYS -> {
                var days = lastTradingDays(today, 7);
                yield new ReportRange(days.isEmpty() ? today : days.get(0), today);
            }
            case MONTH -> new ReportRange(today.withDayOfMonth(1), today);
        };
    }

    /** The days a series shows, oldest first: the last 7 trading days, or every trading day of
     * the month to date. */
    public List<LocalDate> series(DashboardRange range) {
        var today = today();
        if (range == DashboardRange.MONTH) {
            return new ArrayList<>(tradingDaysBetween(today.withDayOfMonth(1), today));
        }
        return lastTradingDays(today, 7);
    }

    /** The UTC instant at which a Johannesburg date and the current time of day fall: "the same
     * time" on an earlier day. */
    public Timestamp sameTimeOn(LocalDate day) {
        var at = day.atTime(localNow().toLocalTime()).atZone(ClockConfig.BUSINESS_ZONE).toInstant();
        return Timestamp.valueOf(UtcTimestamps.toUtc(at));
    }

    public Timestamp nowUtc() {
        return Timestamp.valueOf(UtcTimestamps.nowUtc(clock));
    }

    public static Timestamp startUtc(LocalDate day) {
        return Timestamp.valueOf(UtcTimestamps.toUtc(day.atStartOfDay(ClockConfig.BUSINESS_ZONE).toInstant()));
    }
}
