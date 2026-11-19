package com.beaconculinary.api.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * T0-4a: the rules on changing reports.close-day.first-trading-day (contract section 4.2). A new
 * value (null = empty) is refused when:
 * <ol>
 *   <li>it is after the earliest Z reading's trading day (an existing Z would fall outside the
 *       end-of-day process);</li>
 *   <li>it is before the earliest order's date, or empty while orders exist;</li>
 *   <li>a Z exists and a day with shifts but no Z would fall on or after it and before the earliest
 *       Z: that day would have to be closed, yet comes before Z 1, so Close Day's
 *       PREVIOUS_DAY_NOT_CLOSED would block every later day.</li>
 * </ol>
 * With no orders and no Z readings, anything is accepted.
 */
@Component
public class FirstTradingDayRule {
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final JdbcTemplate jdbc;

    public FirstTradingDayRule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What the rules need to know. earliestZ: the first Z's trading day; earliestOrder: the first
     * order's date; lastUnclosedBeforeZ: the latest trading day with shifts, before earliestZ, that
     * has no Z. Each is null when there is none.
     */
    public record Facts(LocalDate earliestZ, LocalDate earliestOrder, LocalDate lastUnclosedBeforeZ) {
    }

    /** null when the value may be saved, else the message for the 400 field map. */
    public String check(LocalDate value) {
        return check(value, facts());
    }

    public Facts facts() {
        var earliestZ = date("SELECT MIN([trading_day]) FROM [z_readings]");
        var earliestOrder = date("SELECT MIN([order_date]) FROM [orders]");
        LocalDate lastUnclosed = null;
        if (earliestZ != null) {
            var found = jdbc.queryForObject("""
                    SELECT MAX(sf.[trading_day]) FROM [v_shift_facts] sf
                    WHERE sf.[trading_day] < ?
                      AND NOT EXISTS (SELECT 1 FROM [z_readings] z WHERE z.[trading_day] = sf.[trading_day])""",
                    Date.class, Date.valueOf(earliestZ));
            lastUnclosed = found == null ? null : found.toLocalDate();
        }
        return new Facts(earliestZ, earliestOrder, lastUnclosed);
    }

    static String check(LocalDate value, Facts facts) {
        if (value != null && facts.earliestZ() != null && value.isAfter(facts.earliestZ())) {
            return "Must be on or before " + label(facts.earliestZ()) + ", the first day with a Z reading.";
        }
        if (facts.earliestOrder() != null && (value == null || value.isBefore(facts.earliestOrder()))) {
            return "Must be on or after " + label(facts.earliestOrder()) + ", the first day with sales.";
        }
        if (facts.lastUnclosedBeforeZ() != null && (value == null || !value.isAfter(facts.lastUnclosedBeforeZ()))) {
            return "Must be after " + label(facts.lastUnclosedBeforeZ())
                    + ", a trading day before the first Z reading that was never closed.";
        }
        return null;
    }

    private LocalDate date(String sql) {
        var found = jdbc.queryForObject(sql, Date.class);
        return found == null ? null : found.toLocalDate();
    }

    private static String label(LocalDate day) {
        return LABEL.format(day);
    }
}
