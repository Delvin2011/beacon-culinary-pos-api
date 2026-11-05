package com.beaconculinary.api.dashboard;

import java.util.Map;

/** 400 with field-level messages, like the reports: { "range": "Range must be today, 7d or month." }. */
public class DashboardParamException extends RuntimeException {
    private final Map<String, String> errors;

    private DashboardParamException(Map<String, String> errors) {
        super(errors.toString());
        this.errors = errors;
    }

    public static DashboardParamException of(String field, String message) {
        return new DashboardParamException(Map.of(field, message));
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
