package com.beaconculinary.api.tenancy.routing;

import com.beaconculinary.api.tenancy.context.CurrentCustomer;

import java.sql.Connection;

/**
 * T1-2: told once per customer pool, on its first connection, after the collation check passed (e.g. the demo-account
 * report). Use the connection given and don't close it. An exception is logged and doesn't stop the connection.
 */
public interface FirstConnectionListener {
    void onFirstConnection(CurrentCustomer customer, Connection connection) throws Exception;
}
