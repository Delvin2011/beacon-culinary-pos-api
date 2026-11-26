package com.beaconculinary.api.tenancy.routing;

import com.beaconculinary.api.tenancy.CustomerStatus;

/**
 * T1-4: an ACTIVE customer whose database is at an older schema version than the app contains: not served until the
 * operator's migration command has updated it. Answered like any customer that isn't served: 503 CUSTOMER_UNAVAILABLE.
 */
public class CustomerSchemaBehindException extends CustomerNotServedException {

    public CustomerSchemaBehindException(String slug, String databaseVersion, String appVersion) {
        super(slug, CustomerStatus.ACTIVE, "Customer " + slug + ": database at schema version " + databaseVersion
                + ", the app needs " + appVersion + "; not served until it is updated");
    }
}
