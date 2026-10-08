package com.beaconculinary.api.reports;

import java.util.Map;

/** One or more invalid parameters, keyed by parameter name → message (returned as the 400 body). */
public class ReportParamException extends RuntimeException {
    private final Map<String, String> errors;

    public ReportParamException(Map<String, String> errors) {
        super("Invalid report parameters: " + errors);
        this.errors = Map.copyOf(errors);
    }

    public static ReportParamException of(String field, String message) {
        return new ReportParamException(Map.of(field, message));
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
