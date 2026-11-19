package com.beaconculinary.api.offline.exceptions;

/**
 * O4: what a recorded offline sale can be flagged for (contract section 6), with its severity. REVIEW
 * exceptions are a manager's open work; INFO ones are context and never counted as open.
 */
public enum SaleExceptionCode {
    ACCOUNT_APPROVAL_PENDING(Severity.REVIEW),
    ACCOUNT_OVER_CAP(Severity.REVIEW),
    ACCOUNT_INACTIVE(Severity.REVIEW),
    PAYMENT_MISMATCH(Severity.REVIEW),
    TOTALS_MISMATCH(Severity.REVIEW),
    PRICE_MISMATCH(Severity.REVIEW),
    UNKNOWN_ITEM(Severity.REVIEW),
    OVERSOLD(Severity.REVIEW),
    VAT_MISMATCH(Severity.REVIEW),
    CARD_REFERENCE_MISSING(Severity.REVIEW),
    LATE_AFTER_Z(Severity.REVIEW),
    LATE_AFTER_SHIFT_CLOSE(Severity.REVIEW),
    OUTSIDE_GRANT(Severity.REVIEW),
    DEVICE_REVOKED(Severity.REVIEW),
    GRANT_MISMATCH(Severity.REVIEW),
    DUPLICATE_DIFFERS(Severity.REVIEW),
    OFFLINE_NUMBER_CLASH(Severity.REVIEW),
    CLOCK_SKEW(Severity.REVIEW),
    FUTURE_SALE(Severity.REVIEW),
    PERIOD_CLOSED(Severity.INFO),
    ITEM_UNAVAILABLE(Severity.INFO),
    UNLOCKED_OFFLINE(Severity.INFO),
    STOCK_AFTER_COUNT(Severity.INFO);

    public enum Severity {
        INFO, REVIEW
    }

    private final Severity severity;

    SaleExceptionCode(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
