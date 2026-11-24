package com.beaconculinary.api.tenancy.routing;

import com.beaconculinary.api.tenancy.CustomerStatus;

/**
 * T1-2: the current customer exists but isn't ACTIVE (suspended, archived, being provisioned, migration failed), so its
 * database is not used. T1-3 maps it to a response.
 */
public class CustomerNotServedException extends IllegalStateException {
    private final String slug;
    private final CustomerStatus status;

    public CustomerNotServedException(String slug, CustomerStatus status) {
        super("Customer " + slug + " is " + status + ", not served");
        this.slug = slug;
        this.status = status;
    }

    public String slug() {
        return slug;
    }

    public CustomerStatus status() {
        return status;
    }
}
