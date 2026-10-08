package com.beaconculinary.api.reports;

/** How the viewer formats a column's values. MONEY and VARIANCE are JSON numbers with 2
 * decimals (VARIANCE is signed: negative = short, positive = over); INT is a whole number;
 * PERCENT is a percentage figure (12.5 means 12.5%); DATE is "yyyy-MM-dd" (a Johannesburg
 * trading day); DATETIME is an ISO-8601 UTC instant; STATUS values map to tones through the
 * column's statusTones. */
public enum ColumnType {
    TEXT,
    INT,
    MONEY,
    VARIANCE,
    PERCENT,
    DATE,
    DATETIME,
    STATUS
}
