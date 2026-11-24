package com.beaconculinary.api.tenancy.context;

import com.beaconculinary.api.tenancy.Customer;

import java.util.Objects;

/**
 * T1-2: the customer a request or a unit of work is for (docs/2026-11-25-routing-data-source.md). Only who it is: the
 * routing data source looks up its status and database in the customer directory on every new connection.
 */
public record CurrentCustomer(long id, String slug) {

    public CurrentCustomer {
        Objects.requireNonNull(slug, "slug");
        if (slug.isBlank()) {
            throw new IllegalArgumentException("A customer's slug can't be blank");
        }
    }

    public static CurrentCustomer of(Customer customer) {
        return new CurrentCustomer(customer.id(), customer.slug());
    }
}
