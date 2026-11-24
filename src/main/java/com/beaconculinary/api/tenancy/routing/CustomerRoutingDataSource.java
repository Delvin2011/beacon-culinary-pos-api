package com.beaconculinary.api.tenancy.routing;

import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.beaconculinary.api.tenancy.context.NoCurrentCustomerException;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * T1-2: the application's data source in multi-customer mode (docs/2026-11-24-routing-data-source.md). Every new
 * connection goes to the current customer's database ({@link CustomerContext}); with no current customer it throws
 * {@link NoCurrentCustomerException}. There is no default database to fall back to.
 *
 * <p>JPA, JdbcTemplate, native queries, REQUIRES_NEW transactions and TransactionTemplates all take their connections
 * from this one bean, so they all follow the current customer.
 */
public class CustomerRoutingDataSource extends AbstractDataSource implements AutoCloseable {
    private final CustomerPools pools;

    public CustomerRoutingDataSource(CustomerPools pools) {
        this.pools = pools;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return pools.connection(CustomerContext.require());
    }

    /** Not supported: every customer's credentials come from its secrets. */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLException("Customer connections use the customer's own secrets; a login can't be given here");
    }

    public CustomerPools pools() {
        return pools;
    }

    @Override
    public void close() {
        pools.close();
    }
}
