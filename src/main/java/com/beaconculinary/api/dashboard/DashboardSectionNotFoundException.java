package com.beaconculinary.api.dashboard;

/** 404: no such section, or its stage isn't switched on. */
public class DashboardSectionNotFoundException extends RuntimeException {
    public DashboardSectionNotFoundException(String key) {
        super("No dashboard section " + key + ".");
    }
}
