package com.beaconculinary.api.reports;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** One column of the envelope. statusTones (STATUS columns only) maps each value to
 * positive | neutral | warning | negative. */
public record ReportColumn(
        String key,
        String label,
        ColumnType type,
        ColumnTotal total,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> statusTones) {

    public static ReportColumn of(String key, String label, ColumnType type, ColumnTotal total) {
        return new ReportColumn(key, label, type, total, null);
    }
}
