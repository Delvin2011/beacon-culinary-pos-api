package com.beaconculinary.api.endofday;

import com.beaconculinary.api.settings.SiteSettings;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Reports R3: reports.close-day.first-trading-day is where Z numbering starts. Days before it are
 * outside the end-of-day process: they can't be closed, don't block later ones, and their shifts
 * aren't signed off (nor listed or warned about as unsigned). Empty means every day is in the
 * process. Set it to the go-live day. A site setting since T0-4a (default from configuration, env
 * CLOSE_DAY_FIRST_TRADING_DAY); read on every call.
 */
@Component
public class CloseDaySettings {
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final SiteSettings siteSettings;

    public CloseDaySettings(SiteSettings siteSettings) {
        this.siteSettings = siteSettings;
    }

    /** null when not configured. */
    public LocalDate firstTradingDay() {
        return siteSettings.firstTradingDay();
    }

    /** Whether a trading day is part of the end-of-day process (on or after the first trading day). */
    public boolean covers(LocalDate tradingDay) {
        var first = firstTradingDay();
        return first == null || !tradingDay.isBefore(first);
    }

    /** "Z numbering starts 12 Oct 2026", or null when not configured. */
    public String startsLabel() {
        var first = firstTradingDay();
        return first == null ? null : "Z numbering starts " + LABEL.format(first);
    }
}
