package com.beaconculinary.api.common.locale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Currency;

/**
 * T0-2: the one place the business time zone, the currency and the money format come from.
 * Built from {@link SiteLocaleProperties} (see {@link SiteLocaleConfig}); invalid settings stop
 * the app at startup with a message naming the setting.
 *
 * <p>Code takes the zone from here, never from {@code Clock.getZone()}: the Clock bean is built
 * from this zone in production, but tests replace the Clock with one in the machine's zone.
 */
public final class SiteLocale {
    private final ZoneId zone;
    private final String currencyCode;
    private final String currencySymbol;

    private SiteLocale(ZoneId zone, String currencyCode, String currencySymbol) {
        this.zone = zone;
        this.currencyCode = currencyCode;
        this.currencySymbol = currencySymbol;
    }

    /** Validates and builds; throws {@link IllegalStateException} naming the bad setting. */
    public static SiteLocale of(String timeZone, String currencyCode, String currencySymbol) {
        return new SiteLocale(zone(timeZone), currency(currencyCode), symbol(currencySymbol));
    }

    public static SiteLocale from(SiteLocaleProperties properties) {
        return of(properties.getTimeZone(), properties.getCurrencyCode(), properties.getCurrencySymbol());
    }

    private static ZoneId zone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            throw new IllegalStateException("site.time-zone is required (an IANA zone id such as Africa/Johannesburg).");
        }
        try {
            return ZoneId.of(timeZone.trim());
        } catch (DateTimeException e) {
            throw new IllegalStateException("site.time-zone '" + timeZone
                    + "' is not a valid time zone (an IANA zone id such as Africa/Johannesburg).", e);
        }
    }

    private static String currency(String code) {
        if (code == null || !code.trim().matches("[A-Z]{3}")) {
            throw new IllegalStateException("site.currency-code '" + code
                    + "' must be a three-letter upper-case ISO 4217 code such as ZAR.");
        }
        try {
            return Currency.getInstance(code.trim()).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("site.currency-code '" + code + "' is not an ISO 4217 currency code.", e);
        }
    }

    private static String symbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalStateException("site.currency-symbol is required (for example R).");
        }
        return symbol.trim();
    }

    /** The business time zone. */
    public ZoneId zone() {
        return zone;
    }

    public String currencyCode() {
        return currencyCode;
    }

    public String currencySymbol() {
        return currencySymbol;
    }

    /** The business calendar date of an instant (the trading day of a sale, a shift, "today"). */
    public LocalDate businessDate(Instant instant) {
        return LocalDate.ofInstant(instant, zone);
    }

    /** The business local date and time of an instant. */
    public LocalDateTime localDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, zone);
    }

    /** The instant a business day starts (local midnight). */
    public Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }

    /**
     * Money text: "R 1 240,00", "-R 10,00" (space thousands, comma decimals, 2 decimals half-up;
     * null is zero). The symbol is the setting; the grouping and decimal marks are the South
     * African style the dashboard has always used.
     */
    public String formatMoney(BigDecimal value) {
        var amount = (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
        var negative = amount.signum() < 0;
        var digits = amount.abs().toPlainString();
        var whole = digits.substring(0, digits.indexOf('.'));
        var cents = digits.substring(digits.indexOf('.') + 1);
        var grouped = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                grouped.append(' ');
            }
            grouped.append(whole.charAt(i));
        }
        return (negative ? "-" : "") + currencySymbol + " " + grouped + "," + cents;
    }

    @Override
    public String toString() {
        return "SiteLocale[" + zone + ", " + currencyCode + ", " + currencySymbol + "]";
    }
}
