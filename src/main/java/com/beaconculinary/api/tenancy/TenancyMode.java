package com.beaconculinary.api.tenancy;

/**
 * T1-1: {@code app.tenancy.mode}. SINGLE (the default): today's behaviour, one customer database, no platform
 * database. MULTI: the platform database is required and migrated at startup (used from T1-2 on).
 */
public enum TenancyMode {
    SINGLE,
    MULTI
}
