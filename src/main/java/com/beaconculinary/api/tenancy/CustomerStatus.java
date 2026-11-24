package com.beaconculinary.api.tenancy;

/** T1-1: tenants.status. Only ACTIVE customers are served (from T1-3); the others are reported with their status. */
public enum CustomerStatus {
    PROVISIONING,
    ACTIVE,
    SUSPENDED,
    MIGRATION_FAILED,
    ARCHIVED
}
