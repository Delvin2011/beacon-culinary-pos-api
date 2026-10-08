package com.beaconculinary.api.reports;

import java.time.LocalDate;

/**
 * Reports R1 §1.4: how rows are bucketed. Buckets are computed in SQL from the facts views and
 * come back as sortable text keys ("2026-10-05", "2026-10", "07:00"). DAY renders as a DATE
 * column; the others are TEXT labels. WEEK is the ISO week (Monday start), labelled by its
 * Monday. HOUR is the hour of day summed across the whole range.
 */
public enum GroupBy {
    DAY("Date", ColumnType.DATE),
    WEEK("Week", ColumnType.TEXT),
    MONTH("Month", ColumnType.TEXT),
    HOUR("Hour", ColumnType.TEXT);

    private final String columnLabel;
    private final ColumnType columnType;

    GroupBy(String columnLabel, ColumnType columnType) {
        this.columnLabel = columnLabel;
        this.columnType = columnType;
    }

    public String columnLabel() {
        return columnLabel;
    }

    public ColumnType columnType() {
        return columnType;
    }

    /**
     * The SQL bucket expression over a DATE column (and an hour-of-day INT column for HOUR).
     * Built only from these fixed fragments and caller-supplied column names, never from user
     * input. WEEK uses 1900-01-01 (a Monday) as its anchor so it doesn't depend on @@DATEFIRST.
     */
    public String bucketSql(String dateColumn, String hourColumn) {
        return switch (this) {
            case DAY -> "CONVERT(CHAR(10), " + dateColumn + ", 23)";
            case WEEK -> "CONVERT(CHAR(10), DATEADD(DAY, -(DATEDIFF(DAY, '19000101', " + dateColumn + ") % 7), "
                    + dateColumn + "), 23)";
            case MONTH -> "CONVERT(CHAR(7), " + dateColumn + ", 23)";
            case HOUR -> {
                if (hourColumn == null) {
                    throw new IllegalArgumentException("HOUR grouping needs an hour column");
                }
                yield "RIGHT('0' + CAST(" + hourColumn + " AS VARCHAR(2)), 2) + ':00'";
            }
        };
    }

    /** The value shown in the period column for a bucket key. */
    public Object label(String bucket) {
        return switch (this) {
            case DAY -> LocalDate.parse(bucket.trim());
            case WEEK -> "Week of " + bucket.trim();
            case MONTH, HOUR -> bucket.trim();
        };
    }
}
