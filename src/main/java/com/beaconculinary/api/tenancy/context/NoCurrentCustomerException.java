package com.beaconculinary.api.tenancy.context;

/**
 * T1-2: in multi-customer mode, a database access (or anything that needs the customer) with no current customer.
 * Always an error: there is no default database to fall back to.
 */
public class NoCurrentCustomerException extends IllegalStateException {

    public NoCurrentCustomerException() {
        super("No current customer: in multi-customer mode every database access needs one "
                + "(the request's customer, or CustomerContext.runAs for background work)");
    }
}
