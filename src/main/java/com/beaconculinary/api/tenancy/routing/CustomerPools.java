package com.beaconculinary.api.tenancy.routing;

import com.beaconculinary.api.tenancy.Customer;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.secrets.SecretResolver;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * T1-2: one small connection pool per customer database, multi-customer mode only
 * (docs/2026-11-24-routing-data-source.md).
 *
 * <ul>
 *   <li>A pool is made on its customer's first connection, once even when several threads ask together, from the
 *       directory's database location; the login and password come from {@link SecretResolver} (the JDBC URL never
 *       holds credentials). Making it opens no connection.</li>
 *   <li>Its first connection checks the database's collation against the expected one (a mismatch closes the pool
 *       and refuses), logs the schema version found, and tells the {@link FirstConnectionListener}s.</li>
 *   <li>T1-4: it also compares that version with the newest one the app contains ({@link AppSchemaVersion}). Behind:
 *       the customer isn't served ({@link CustomerSchemaBehindException}, 503 CUSTOMER_UNAVAILABLE, logged at ERROR
 *       once per version found, T1-6), and its requests are refused without connecting until {@code schema-recheck}
 *       has passed; then the next
 *       request checks again, so it comes back by itself once the operator's migration command has run. Ahead (a
 *       newer app updated it): served, with a WARN, as migrations stay backward compatible.</li>
 *   <li>Only ACTIVE customers get a connection. {@link #sweep()} (every minute) closes a pool nobody used for
 *       {@code evict-after}, and at once when the directory shows its customer no longer ACTIVE or its database moved;
 *       a connection request notices those changes too.</li>
 * </ul>
 */
public class CustomerPools implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CustomerPools.class);

    private final CustomerDirectory directory;
    private final SecretResolver secrets;
    private final CustomerPoolProperties properties;
    private final Clock clock;
    private final Supplier<List<FirstConnectionListener>> listeners;
    private final ConcurrentHashMap<String, Pool> pools = new ConcurrentHashMap<>();
    private final AtomicLong created = new AtomicLong();
    private final MigrationVersion appVersion;
    /** T1-4: customers found behind, by slug: refused without connecting until the recheck time. */
    private final ConcurrentHashMap<String, Behind> behind = new ConcurrentHashMap<>();
    /**
     * T1-6: the version each customer was last reported behind at. The ERROR is logged once per version; a recheck that
     * finds the same version logs at DEBUG (jobs check every minute). Cleared when the customer is served again.
     */
    private final ConcurrentHashMap<String, String> reportedBehind = new ConcurrentHashMap<>();

    public CustomerPools(CustomerDirectory directory, SecretResolver secrets, CustomerPoolProperties properties,
                         Clock clock, List<FirstConnectionListener> listeners) {
        this(directory, secrets, properties, clock, () -> listeners);
    }

    /** As above, with the listeners looked up at each pool's first connection (some use the data source themselves). */
    public CustomerPools(CustomerDirectory directory, SecretResolver secrets, CustomerPoolProperties properties,
                         Clock clock, Supplier<List<FirstConnectionListener>> listeners) {
        properties.validate();
        this.directory = directory;
        this.secrets = secrets;
        this.properties = properties;
        this.clock = clock;
        this.listeners = listeners;
        this.appVersion = AppSchemaVersion.latest();
    }

    /** A connection to this customer's database, from its pool (made now if it has none). */
    public Connection connection(CurrentCustomer current) throws SQLException {
        var customer = served(current);
        var found = behind.get(current.slug());
        if (found != null) {
            if (clock.instant().isBefore(found.recheckAt())) {
                throw new CustomerSchemaBehindException(current.slug(), found.version(), appVersion.getVersion());
            }
            behind.remove(current.slug(), found);
        }
        var key = Key.of(customer.database());
        while (true) {
            var pool = pools.computeIfAbsent(current.slug(), slug -> open(customer, key));
            if (!pool.key.equals(key)) {
                retire(pool, "database location changed");
                continue;
            }
            if (pool.isClosed()) {
                pools.remove(current.slug(), pool);
                continue;
            }
            return pool.connection(current);
        }
    }

    /** Closes the pools that are idle too long or whose customer is no longer served or moved. Run every minute. */
    public void sweep() {
        var now = clock.instant();
        for (var pool : pools.values()) {
            var reason = reasonToClose(pool, now);
            if (reason != null) {
                retire(pool, reason);
            }
        }
    }

    /** The pools' state for /health: how many are open and how many failed their last connection. No names. */
    public State state() {
        int open = 0;
        int failing = 0;
        for (var pool : pools.values()) {
            open++;
            if (pool.lastFailed) {
                failing++;
            }
        }
        return new State(open, failing);
    }

    /** Whether this customer has an open pool (tests, diagnostics). */
    public boolean hasPool(String slug) {
        return pools.containsKey(slug);
    }

    /** How many pools were made since start (tests, diagnostics). */
    public long poolsCreated() {
        return created.get();
    }

    @Override
    public void close() {
        for (var pool : pools.values()) {
            retire(pool, "shutting down");
        }
    }

    public record State(int open, int failing) {
    }

    private record Behind(String version, Instant recheckAt) {
    }

    private Customer served(CurrentCustomer current) {
        var found = directory.findBySlug(current.slug()).filter(customer -> customer.id() == current.id());
        if (found.isEmpty()) {
            Optional.ofNullable(pools.get(current.slug())).ifPresent(pool -> retire(pool, "no longer in the directory"));
            throw new UnknownCustomerException(current.slug());
        }
        var customer = found.get();
        if (!customer.isActive()) {
            Optional.ofNullable(pools.get(current.slug())).ifPresent(pool -> retire(pool, "customer is " + customer.status()));
            throw new CustomerNotServedException(customer.slug(), customer.status());
        }
        if (customer.database() == null || customer.database().databaseName() == null) {
            throw new IllegalStateException("Customer " + customer.slug() + " is ACTIVE but has no database registered");
        }
        return customer;
    }

    private String reasonToClose(Pool pool, Instant now) {
        Optional<Customer> customer;
        try {
            customer = directory.findBySlug(pool.slug);
        } catch (RuntimeException e) {
            // The directory keeps its last good snapshot; if even that fails, leave the pool to the idle rule.
            if (pool.idleSince(now) && pool.lastUsed.plus(properties.getEvictAfter()).compareTo(now) <= 0) {
                return "idle for " + properties.getEvictAfter();
            }
            return null;
        }
        if (customer.isEmpty() || customer.get().id() != pool.customerId) {
            return "no longer in the directory";
        }
        if (!customer.get().isActive()) {
            return "customer is " + customer.get().status();
        }
        if (customer.get().database() == null || !Key.of(customer.get().database()).equals(pool.key)) {
            return "database location changed";
        }
        if (pool.idleSince(now) && pool.lastUsed.plus(properties.getEvictAfter()).compareTo(now) <= 0) {
            return "idle for " + properties.getEvictAfter();
        }
        return null;
    }

    private void retire(Pool pool, String reason) {
        retire(pool, reason, false);
    }

    private void retire(Pool pool, String reason, boolean quiet) {
        if (pools.remove(pool.slug, pool)) {
            pool.close();
            (quiet ? log.atDebug() : log.atInfo()).addKeyValue("customer", pool.slug)
                    .log("tenancy: customer {} pool closed ({})", pool.slug, reason);
        }
    }

    private Pool open(Customer customer, Key key) {
        var config = new HikariConfig();
        config.setPoolName("customer-" + customer.slug());
        config.setJdbcUrl(key.jdbcUrl());
        // Resolved now and handed to the pool only; never logged, never in the URL.
        config.setUsername(secrets.resolve(key.loginSecretName()));
        config.setPassword(secrets.resolve(key.passwordSecretName()));
        config.setMaximumPoolSize(properties.getMaximumSize());
        config.setMinimumIdle(0);
        config.setIdleTimeout(properties.getIdleTimeout().toMillis());
        config.setConnectionTimeout(properties.getConnectionTimeout().toMillis());
        // No connection now: the first one is made when a request needs it.
        config.setInitializationFailTimeout(-1);
        config.setRegisterMbeans(false);
        var pool = new Pool(customer.id(), customer.slug(), key, new HikariDataSource(config), clock.instant());
        created.incrementAndGet();
        return pool;
    }

    /** What makes two database locations the same pool: where it is and which secrets log in (not its migration state). */
    private record Key(String serverHost, int serverPort, String databaseName, String connectionOptions,
                       String loginSecretName, String passwordSecretName, String expectedCollation) {

        static Key of(Customer.DatabaseLocation location) {
            return new Key(location.serverHost(), location.serverPort(), location.databaseName(),
                    location.connectionOptions(), location.loginSecretName(), location.passwordSecretName(),
                    location.expectedCollation());
        }

        String jdbcUrl() {
            var url = new StringBuilder("jdbc:sqlserver://").append(serverHost).append(':').append(serverPort)
                    .append(";databaseName={").append(databaseName.replace("}", "}}")).append('}');
            if (connectionOptions != null && !connectionOptions.isBlank()) {
                url.append(';').append(connectionOptions.trim());
            }
            return url.toString();
        }
    }

    private final class Pool {
        private final long customerId;
        private final String slug;
        private final Key key;
        private final HikariDataSource dataSource;
        private volatile Instant lastUsed;
        private volatile boolean checked;
        private volatile boolean lastFailed;

        Pool(long customerId, String slug, Key key, HikariDataSource dataSource, Instant now) {
            this.customerId = customerId;
            this.slug = slug;
            this.key = key;
            this.dataSource = dataSource;
            this.lastUsed = now;
        }

        Connection connection(CurrentCustomer current) throws SQLException {
            Connection connection;
            try {
                connection = dataSource.getConnection();
                lastFailed = false;
            } catch (SQLException e) {
                lastFailed = true;
                throw e;
            }
            lastUsed = clock.instant();
            if (!checked) {
                firstConnection(current, connection);
            }
            return connection;
        }

        private synchronized void firstConnection(CurrentCustomer current, Connection connection) throws SQLException {
            if (checked) {
                return;
            }
            var alreadyReported = false;
            try {
                var collation = query(connection, "SELECT CONVERT(nvarchar(128), DATABASEPROPERTYEX(DB_NAME(), 'Collation'))");
                if (collation == null || !collation.equalsIgnoreCase(key.expectedCollation())) {
                    throw new CustomerDatabaseMismatchException("Customer " + slug + ": database " + key.databaseName()
                            + " has collation " + collation + ", expected " + key.expectedCollation() + "; not used");
                }
                var version = schemaVersion(connection);
                var comparison = AppSchemaVersion.compare(version);
                if (comparison < 0) {
                    var recheckAt = clock.instant().plus(properties.getSchemaRecheck());
                    behind.put(slug, new Behind(version, recheckAt));
                    // T1-6: once per version found; a recheck finding the same version is DEBUG.
                    alreadyReported = String.valueOf(version).equals(reportedBehind.put(slug, String.valueOf(version)));
                    (alreadyReported ? log.atDebug() : log.atError())
                            .addKeyValue("customer", slug).addKeyValue("schemaVersion", version)
                            .addKeyValue("appSchemaVersion", appVersion.getVersion())
                            .log("tenancy: customer {} database {} is at schema version {}, behind the app's {}: not served "
                                            + "until the migration command updates it (checked again after {})",
                                    slug, key.databaseName(), version, appVersion.getVersion(), properties.getSchemaRecheck());
                    throw new CustomerSchemaBehindException(slug, version, appVersion.getVersion());
                }
                reportedBehind.remove(slug);
                log.atInfo().addKeyValue("customer", slug).addKeyValue("schemaVersion", version)
                        .log("tenancy: customer {} pool opened (database {} on {}), schema at version {}",
                                slug, key.databaseName(), key.serverHost(), version);
                if (comparison > 0) {
                    log.atWarn().addKeyValue("customer", slug).addKeyValue("schemaVersion", version)
                            .addKeyValue("appSchemaVersion", appVersion.getVersion())
                            .log("tenancy: customer {} database is at schema version {}, ahead of the app's {}: served "
                                    + "(migrations stay backward compatible)", slug, version, appVersion.getVersion());
                }
                for (var listener : listeners.get()) {
                    try {
                        listener.onFirstConnection(current, connection);
                    } catch (Exception e) {
                        log.warn("tenancy: customer {} first-connection check {} failed", slug,
                                listener.getClass().getSimpleName(), e);
                    }
                }
                checked = true;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.close();
                } finally {
                    // T1-6: quiet (DEBUG) when it's a database still behind at a version already reported.
                    retire(this, "first connection refused", alreadyReported);
                }
                throw e;
            }
        }

        boolean idleSince(Instant now) {
            var bean = dataSource.getHikariPoolMXBean();
            return bean == null || bean.getActiveConnections() == 0;
        }

        boolean isClosed() {
            return dataSource.isClosed();
        }

        void close() {
            dataSource.close();
        }
    }

    private static String schemaVersion(Connection connection) throws SQLException {
        if (query(connection, "SELECT CONVERT(nvarchar(10), OBJECT_ID('flyway_schema_history'))") == null) {
            return "none (no flyway_schema_history)";
        }
        var version = query(connection, "SELECT TOP (1) [version] FROM [flyway_schema_history] "
                + "WHERE [success] = 1 AND [version] IS NOT NULL ORDER BY [installed_rank] DESC");
        return version == null ? "none" : version;
    }

    private static String query(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
