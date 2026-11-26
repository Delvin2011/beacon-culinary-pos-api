package com.beaconculinary.api.tenancy;

import java.util.List;

/**
 * T1-1: a customer as the directory knows it: who it is, its status, its addresses (the primary first) and where its
 * database is. No secret values: the database location carries only the NAMES of the login and password secrets
 * (resolved with {@link com.beaconculinary.api.tenancy.secrets.SecretResolver} when a connection is made, T1-2).
 */
public record Customer(long id, String slug, String displayName, CustomerStatus status, List<String> hosts,
                       DatabaseLocation database) {

    public Customer {
        hosts = List.copyOf(hosts);
    }

    /** The primary host, or null when the customer has none yet. */
    public String primaryHost() {
        return hosts.isEmpty() ? null : hosts.get(0);
    }

    public boolean isActive() {
        return status == CustomerStatus.ACTIVE;
    }

    /**
     * Where a customer's database is. Null fields mean not registered yet (a customer being provisioned). The login and
     * password secrets are the app login's; the migration ones (T1-4) are resolved only by the operator's migration
     * command, never by the app.
     */
    public record DatabaseLocation(String serverHost, int serverPort, String databaseName, String connectionOptions,
                                   String loginSecretName, String passwordSecretName, boolean builtFromBaseline,
                                   String expectedCollation, String lastMigratedVersion, String migrationLoginSecretName,
                                   String migrationPasswordSecretName) {
    }
}
