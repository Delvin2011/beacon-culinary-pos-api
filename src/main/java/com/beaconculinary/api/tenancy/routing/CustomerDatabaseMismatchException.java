package com.beaconculinary.api.tenancy.routing;

/**
 * T1-2: a customer's database isn't what the platform database says it should be (today: its collation). The pool is
 * closed and nothing is read or written. Names the customer and the values, never credentials.
 */
public class CustomerDatabaseMismatchException extends IllegalStateException {

    public CustomerDatabaseMismatchException(String message) {
        super(message);
    }
}
