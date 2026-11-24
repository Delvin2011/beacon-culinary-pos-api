package com.beaconculinary.api.tenancy.routing;

/** T1-2: the current customer isn't in the customer directory (or no longer has that id). */
public class UnknownCustomerException extends IllegalStateException {
    private final String slug;

    public UnknownCustomerException(String slug) {
        super("Customer " + slug + " is not in the customer directory");
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }
}
