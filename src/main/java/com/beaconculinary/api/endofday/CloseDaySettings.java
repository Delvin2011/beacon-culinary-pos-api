package com.beaconculinary.api.endofday;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Reports R3: reports.close-day.first-trading-day (env CLOSE_DAY_FIRST_TRADING_DAY) is where Z
 * numbering starts. Days before it are outside the end-of-day process: they can't be closed,
 * don't block later ones, and their shifts aren't signed off (nor listed or warned about as
 * unsigned). Empty means every day is in the process. Set it to the go-live day.
 */
@Component
public class CloseDaySettings {
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final LocalDate firstTradingDay;

    public CloseDaySettings(@Value("${reports.close-day.first-trading-day:#{null}}") LocalDate firstTradingDay) {
        this.firstTradingDay = firstTradingDay;
    }

    /** null when not configured. */
    public LocalDate firstTradingDay() {
        return firstTradingDay;
    }

    /** Whether a trading day is part of the end-of-day process (on or after the first trading day). */
    public boolean covers(LocalDate tradingDay) {
        return firstTradingDay == null || !tradingDay.isBefore(firstTradingDay);
    }

    /** "Z numbering starts 12 Oct 2026", or null when not configured. */
    public String startsLabel() {
        return firstTradingDay == null ? null : "Z numbering starts " + LABEL.format(firstTradingDay);
    }
}
