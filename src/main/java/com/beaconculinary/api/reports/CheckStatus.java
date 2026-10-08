package com.beaconculinary.api.reports;

/** PASS = reconciles. WARN = informational (e.g. a shift still open). FAIL = integrity alarm. */
public enum CheckStatus {
    PASS,
    WARN,
    FAIL
}
