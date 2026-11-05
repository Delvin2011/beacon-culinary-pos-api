package com.beaconculinary.api.dashboard;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.common.UtcTimestamps;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/** Numbers and labels as the dashboard shows them. Money stays a 2-decimal number in JSON; only
 * the human-readable "secondary" lines use the rand format ("R 1 240,00"). */
public final class DashboardFormat {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private DashboardFormat() {
    }

    public static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /** "R 1 240,00" (South African style: space thousands, comma decimals). */
    public static String rand(BigDecimal value) {
        var amount = money(value);
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
        return (negative ? "-R " : "R ") + grouped + "," + cents;
    }

    /** a ÷ b × 100 to one decimal; null when b is zero. */
    public static BigDecimal percent(BigDecimal a, BigDecimal b) {
        if (b == null || b.signum() == 0) {
            return null;
        }
        return a.multiply(HUNDRED).divide(b, 1, RoundingMode.HALF_UP);
    }

    /** round((value − base) ÷ base × 100) as a whole percent; null when there is no base or it is zero. */
    public static Integer delta(BigDecimal value, BigDecimal base) {
        if (value == null || base == null || base.signum() == 0) {
            return null;
        }
        return value.subtract(base).multiply(HUNDRED).divide(base, 0, RoundingMode.HALF_UP).intValueExact();
    }

    /**
     * Shares of a total, one decimal each, adding up to exactly 100.0 (largest remainder: each
     * share is rounded down to a tenth, and the tenths left over go to the largest remainders,
     * earliest first on a tie). Empty when the total is zero.
     */
    public static List<BigDecimal> shares(List<BigDecimal> amounts) {
        var total = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            return List.of();
        }
        var tenths = new ArrayList<BigDecimal>();
        var floors = new long[amounts.size()];
        long given = 0;
        for (int i = 0; i < amounts.size(); i++) {
            var exact = amounts.get(i).multiply(BigDecimal.valueOf(1000)).divide(total, 10, RoundingMode.HALF_UP);
            tenths.add(exact);
            floors[i] = exact.setScale(0, RoundingMode.FLOOR).longValue();
            given += floors[i];
        }
        var order = IntStream.range(0, amounts.size()).boxed()
                .sorted(Comparator.<Integer, BigDecimal>comparing(i -> tenths.get(i).subtract(BigDecimal.valueOf(floors[i])))
                        .reversed().thenComparing(i -> i))
                .toList();
        for (int k = 0; given < 1000 && k < order.size(); k++, given++) {
            floors[order.get(k)]++;
        }
        var shares = new ArrayList<BigDecimal>();
        for (var tenth : floors) {
            shares.add(BigDecimal.valueOf(tenth, 1));
        }
        return shares;
    }

    /** "Tue 13 Oct". */
    public static String day(LocalDate day) {
        return DAY.format(day);
    }

    /** "Mon 5", for chart axes. */
    public static String shortDay(LocalDate day) {
        return SHORT_DAY.format(day);
    }

    /** "Mon". */
    public static String weekday(LocalDate day) {
        return day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /** "Monday". */
    public static String weekdayLong(LocalDate day) {
        return day.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /** Johannesburg "HH:mm" of a stored UTC value. */
    public static String time(LocalDateTime utc) {
        return utc == null ? null : TIME.format(local(utc));
    }

    public static LocalDateTime local(LocalDateTime utc) {
        return LocalDateTime.ofInstant(UtcTimestamps.toInstant(utc), ClockConfig.BUSINESS_ZONE);
    }

    public static Instant instant(LocalDateTime utc) {
        return UtcTimestamps.toInstant(utc);
    }

    /** "Thandiwe M." from "Thandiwe Mokoena"; a one-word name stays as it is. */
    public static String shortName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        var parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        return parts[0] + " " + Character.toUpperCase(parts[parts.length - 1].charAt(0)) + ".";
    }

    /** "1 shift" / "2 shifts". */
    public static String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    /** Minutes between two stored UTC values, to one decimal. */
    public static BigDecimal minutes(LocalDateTime fromUtc, LocalDateTime toUtc) {
        var seconds = java.time.Duration.between(fromUtc, toUtc).getSeconds();
        return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
    }

    /** Whole minutes since a stored UTC value, never negative. */
    public static long minutesSince(LocalDateTime fromUtc, Instant now) {
        return Math.max(0, java.time.Duration.between(UtcTimestamps.toInstant(fromUtc), now).toMinutes());
    }
}
