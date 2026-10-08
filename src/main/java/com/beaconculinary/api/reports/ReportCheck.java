package com.beaconculinary.api.reports;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/** A reconciliation check shown with every report. difference = actual - expected. unit says how
 * to format expected/actual/difference: MONEY amounts, or a COUNT of items (e.g. failing shifts).
 * detail is an optional plain-language explanation (e.g. "1 shift is still open"). */
public record ReportCheck(
        String key,
        String label,
        CheckStatus status,
        Unit unit,
        BigDecimal expected,
        BigDecimal actual,
        BigDecimal difference,
        @JsonInclude(JsonInclude.Include.NON_NULL) String detail) {

    public enum Unit {
        MONEY,
        COUNT
    }

    /** PASS when the two amounts agree to the cent, otherwise FAIL. */
    public static ReportCheck compare(String key, String label, BigDecimal expected, BigDecimal actual, String detail) {
        var e = ReportValues.money(expected);
        var a = ReportValues.money(actual);
        var difference = a.subtract(e);
        return new ReportCheck(key, label, difference.signum() == 0 ? CheckStatus.PASS : CheckStatus.FAIL,
                Unit.MONEY, e, a, difference, detail);
    }

    /** A count of failing items: PASS at zero, otherwise FAIL. Expected is always 0. */
    public static ReportCheck failures(String key, String label, long failing, String detail) {
        return count(key, label, failing, failing == 0 ? CheckStatus.PASS : CheckStatus.FAIL, detail);
    }

    /** A count of items against an expected 0, with the given status. */
    public static ReportCheck count(String key, String label, long actual, CheckStatus status, String detail) {
        var count = BigDecimal.valueOf(actual);
        return new ReportCheck(key, label, status, Unit.COUNT, BigDecimal.ZERO, count, count, detail);
    }

    public ReportCheck withStatus(CheckStatus newStatus, String newDetail) {
        return new ReportCheck(key, label, newStatus, unit, expected, actual, difference, newDetail);
    }
}
