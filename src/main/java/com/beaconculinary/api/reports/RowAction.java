package com.beaconculinary.api.reports;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Reports R3: an action on one row, carried in the row under "_actions". method and path are
 * null for OPEN_REPORT, which only navigates. onSuccess tells the viewer what to do next:
 * RELOAD the report, PRINT_RECEIPT from the response, or OPEN_REPORT (report + params).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RowAction(String key, String label, String method, String path, String confirm, OnSuccess onSuccess,
                        String report, Map<String, Object> params) {
    public static final String ROW_KEY = "_actions";

    public enum OnSuccess {
        RELOAD,
        PRINT_RECEIPT,
        OPEN_REPORT
    }

    public static RowAction post(String key, String label, String path, String confirm, OnSuccess onSuccess) {
        return new RowAction(key, label, "POST", path, confirm, onSuccess, null, null);
    }

    public static RowAction openReport(String key, String label, String report, Map<String, Object> params) {
        return new RowAction(key, label, null, null, null, OnSuccess.OPEN_REPORT, report, params);
    }
}
