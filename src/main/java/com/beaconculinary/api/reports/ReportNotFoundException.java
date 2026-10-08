package com.beaconculinary.api.reports;

public class ReportNotFoundException extends RuntimeException {
    public ReportNotFoundException(String key) {
        super("No report with key " + key + ".");
    }
}
