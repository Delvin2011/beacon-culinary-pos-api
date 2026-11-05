package com.beaconculinary.api.dashboard;

/** 500 for one section only: the other sections, each fetched on its own, are unaffected. */
public class DashboardSectionFailedException extends RuntimeException {
    private final String key;

    public DashboardSectionFailedException(String key, Throwable cause) {
        super("Dashboard section " + key + " failed.", cause);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
