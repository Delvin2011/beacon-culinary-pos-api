package com.beaconculinary.api.tenancy.routing;

import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.baseline.ThrowawayDatabase;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.beaconculinary.api.tenancy.CustomerStatus;
import com.beaconculinary.api.tenancy.PlatformDatabase;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.beaconculinary.api.tenancy.context.NoCurrentCustomerException;
import com.beaconculinary.api.tenancy.secrets.MissingSecretException;
import com.beaconculinary.api.tenancy.secrets.SecretResolver;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1-2: customer pools and the routing data source on throwaway databases: a platform database and customer databases
 * a and b (the right collation), c (the wrong one). Each connection goes to the current customer's database; pools
 * are made lazily, once under concurrent first use, closed when idle, suspended or moved; credentials come from the
 * SecretResolver and never appear in the output. T1-4: a and b are at the app's schema version; d is behind (V115) and
 * isn't served until it is updated and the recheck time has passed; e is ahead (a test-only V117) and is served.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Tag(MultiCustomerIntegrationTest.TAG) // T1-10: the cross-customer suite
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerPoolsIntegrationTests {
    private static final Pattern SERVER = Pattern.compile("^jdbc:sqlserver://([A-Za-z0-9.-]+)(?::(\\d+))?(;.*)?$");
    private static final Duration EVICT_AFTER = Duration.ofMinutes(30);

    @Autowired
    private Environment environment;

    private final List<ThrowawayDatabase> databases = new ArrayList<>();
    private ThrowawayDatabase platform;
    private JdbcTemplate platformJdbc;
    private String serverHost;
    private int serverPort;
    private String options;
    private final Map<String, String> secrets = new HashMap<>();
    private final Map<String, String> databaseNames = new HashMap<>();
    private MutableClock clock;
    private ThrowawayDatabase behindDatabase;
    private CustomerPools pools;
    private CustomerRoutingDataSource routing;
    private final AtomicInteger firstConnections = new AtomicInteger();

    @BeforeAll
    void createDatabases() throws SQLException {
        var url = environment.getRequiredProperty("spring.datasource.url");
        var username = environment.getRequiredProperty("spring.datasource.username");
        var password = environment.getRequiredProperty("spring.datasource.password");
        Assumptions.assumeTrue(ThrowawayDatabase.canCreate(url, username, password), "the test login can't create databases");
        var server = SERVER.matcher(url);
        Assumptions.assumeTrue(server.matches(), "the test URL names no plain host and port");
        serverHost = server.group(1);
        serverPort = server.group(2) == null ? 1433 : Integer.parseInt(server.group(2));
        options = optionsWithoutDatabase(server.group(3));

        platform = ThrowawayDatabase.create(url, username, password, "Platform", "SQL_Latin1_General_CP1_CI_AS");
        databases.add(platform);
        platform.flyway(PlatformDatabase.LOCATION).migrate();
        platformJdbc = new JdbcTemplate(new DriverManagerDataSource(platform.url(), platform.username(), platform.password()));

        for (var slug : List.of("customera", "customerb", "customerc", "customerd", "customere")) {
            var collation = slug.equals("customerc") ? "Latin1_General_CI_AS" : "SQL_Latin1_General_CP1_CI_AS";
            var db = ThrowawayDatabase.create(url, username, password, "Pool", collation);
            databases.add(db);
            databaseNames.put(slug, db.name());
            // T1-4: a schema history the version check reads (c is refused for its collation before that).
            switch (slug) {
                case "customerd" -> {
                    Flyway.configure().dataSource(db.url(), username, password).locations("classpath:db/migration")
                            .target("115").cleanDisabled(true).load().migrate();
                    behindDatabase = db;
                }
                case "customere" -> db.flyway("classpath:db/migration", "classpath:db/baseline", "classpath:db/t1-4-probe").migrate();
                case "customerc" -> { }
                default -> db.flyway("classpath:db/migration", "classpath:db/baseline").migrate();
            }
            try (var connection = db.connect(); var st = connection.createStatement()) {
                st.execute("CREATE TABLE marker (owner NVARCHAR(40) NOT NULL)");
                st.execute("INSERT INTO marker (owner) VALUES ('" + slug + "')");
            }
            secrets.put(slug + "-db-login", username);
            secrets.put(slug + "-db-password", password);
        }
    }

    @AfterAll
    void dropDatabases() throws SQLException {
        for (var db : databases) {
            db.close();
        }
    }

    @BeforeEach
    void freshDirectoryAndPools() {
        platformJdbc.update("DELETE FROM provisioning_runs");
        platformJdbc.update("DELETE FROM tenant_databases");
        platformJdbc.update("DELETE FROM tenant_hosts");
        platformJdbc.update("DELETE FROM tenants");
        register("customera", "ACTIVE", databaseNames.get("customera"), "customera");
        register("customerb", "ACTIVE", databaseNames.get("customerb"), "customerb");
        register("customerc", "ACTIVE", databaseNames.get("customerc"), "customerc");
        register("customerd", "ACTIVE", databaseNames.get("customerd"), "customerd");
        register("customere", "ACTIVE", databaseNames.get("customere"), "customere");
        register("suspended", "SUSPENDED", "T07NowhereSuspendedTest", "suspended");
        register("archived", "ARCHIVED", "T07NowhereArchivedTest", "archived");
        register("nosecrets", "ACTIVE", "T07NowhereNoSecretsTest", "nosecrets");
        clock = new MutableClock(Clock.fixed(Instant.parse("2026-11-25T08:00:00Z"), ZoneOffset.UTC));
        firstConnections.set(0);
        var properties = new CustomerPoolProperties();
        properties.setEvictAfter(EVICT_AFTER);
        properties.setConnectionTimeout(Duration.ofSeconds(5));
        SecretResolver resolver = name -> {
            var value = secrets.get(name);
            if (value == null) {
                throw new MissingSecretException(name, "the test's secret map");
            }
            return value;
        };
        // TTL 0: every lookup reads the platform database, so a status change is seen at once.
        var directory = new CustomerDirectory(platformJdbc, clock, Duration.ZERO);
        pools = new CustomerPools(directory, resolver, properties, clock,
                List.of((customer, connection) -> firstConnections.incrementAndGet()));
        routing = new CustomerRoutingDataSource(pools);
    }

    @AfterEach
    void closePools() {
        routing.close();
    }

    @Test
    void eachConnection_goesToTheCurrentCustomersDatabase_andNoCustomerIsAnError() {
        assertThat(as("customera", () -> owner())).isEqualTo("customera");
        assertThat(as("customerb", () -> owner())).isEqualTo("customerb");
        assertThat(as("customera", () -> owner())).isEqualTo("customera");
        assertThatThrownBy(() -> routing.getConnection()).isInstanceOf(NoCurrentCustomerException.class);
        assertThat(pools.state()).isEqualTo(new CustomerPools.State(2, 0));
    }

    @Test
    void aPool_isMadeLazily_once_evenUnderConcurrentFirstUse() throws Exception {
        assertThat(pools.hasPool("customera")).isFalse();
        assertThat(pools.poolsCreated()).isZero();

        var threads = 20;
        var start = new CountDownLatch(1);
        var owners = new ConcurrentLinkedQueue<String>();
        var executor = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    start.await();
                    owners.add(as("customera", this::owner));
                    return null;
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(owners).hasSize(threads).containsOnly("customera");
        assertThat(pools.poolsCreated()).as("one pool for 20 concurrent first uses").isEqualTo(1);
        assertThat(firstConnections.get()).as("the first-connection check ran once").isEqualTo(1);
    }

    @Test
    void anIdlePool_isClosedAfterEvictAfter_butNotWhileAConnectionIsInUse() throws SQLException {
        as("customera", this::owner);
        clock.setInstant(clock.instant().plus(EVICT_AFTER).minusSeconds(1));
        pools.sweep();
        assertThat(pools.hasPool("customera")).as("not idle long enough").isTrue();

        try (var held = CustomerContext.callAs(customer("customera"), this::rawConnection)) {
            clock.setInstant(clock.instant().plus(EVICT_AFTER).plusSeconds(1));
            pools.sweep();
            assertThat(pools.hasPool("customera")).as("a connection is in use").isTrue();
        }
        clock.setInstant(clock.instant().plus(EVICT_AFTER).plusSeconds(1));
        pools.sweep();
        assertThat(pools.hasPool("customera")).as("idle past evict-after").isFalse();
        assertThat(as("customera", this::owner)).as("a new pool on the next use").isEqualTo("customera");
        assertThat(pools.poolsCreated()).isEqualTo(2);
    }

    @Test
    void aSuspendedCustomer_hasItsPoolClosed_andIsRefused() {
        as("customera", this::owner);
        var current = customer("customera");
        platformJdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE slug = 'customera'");
        pools.sweep();
        assertThat(pools.hasPool("customera")).as("closed by the sweep").isFalse();
        assertThatThrownBy(() -> CustomerContext.callAs(current, this::rawConnection))
                .isInstanceOfSatisfying(CustomerNotServedException.class, e -> {
                    assertThat(e.slug()).isEqualTo("customera");
                    assertThat(e.status()).isEqualTo(CustomerStatus.SUSPENDED);
                });

        as("customerb", this::owner);
        var b = customer("customerb");
        platformJdbc.update("UPDATE tenants SET status = 'ARCHIVED' WHERE slug = 'customerb'");
        assertThatThrownBy(() -> CustomerContext.callAs(b, this::rawConnection))
                .isInstanceOfSatisfying(CustomerNotServedException.class, e -> assertThat(e.status()).isEqualTo(CustomerStatus.ARCHIVED));
        assertThat(pools.hasPool("customerb")).as("closed at once by the refused request").isFalse();
    }

    @Test
    void suspendedOrArchivedCustomers_areRefused_withoutAPool() {
        for (var slug : List.of("suspended", "archived")) {
            var current = customer(slug);
            assertThatThrownBy(() -> CustomerContext.callAs(current, this::rawConnection))
                    .as(slug).isInstanceOf(CustomerNotServedException.class).hasMessageContaining("not served");
        }
        assertThat(pools.poolsCreated()).isZero();
    }

    @Test
    void aMovedDatabase_getsANewPool() {
        as("customerb", this::owner);
        platformJdbc.update("UPDATE tenant_databases SET connection_options = ? WHERE tenant_id = ?",
                (options == null ? "" : options + ";") + "loginTimeout=20", tenantId("customerb"));
        assertThat(as("customerb", this::owner)).isEqualTo("customerb");
        assertThat(pools.poolsCreated()).as("the changed location made a new pool").isEqualTo(2);
        assertThat(pools.state().open()).isEqualTo(1);
    }

    @Test
    void aCollationMismatch_isRefused_andThePoolClosed() {
        var current = customer("customerc");
        assertThatThrownBy(() -> CustomerContext.callAs(current, this::rawConnection))
                .isInstanceOf(CustomerDatabaseMismatchException.class)
                .hasMessageContaining("collation Latin1_General_CI_AS, expected SQL_Latin1_General_CP1_CI_AS");
        assertThat(pools.hasPool("customerc")).isFalse();
        assertThat(firstConnections.get()).as("listeners not told").isZero();
    }

    @Test
    void credentialsComeFromTheSecretResolver_aMissingSecretIsNamed_andNoSecretIsPrinted(CapturedOutput output) {
        var current = customer("nosecrets");
        assertThatThrownBy(() -> CustomerContext.callAs(current, this::rawConnection))
                .isInstanceOf(MissingSecretException.class)
                .hasMessageContaining("nosecrets-db-login");
        assertThat(pools.hasPool("nosecrets")).isFalse();

        as("customera", this::owner);
        assertThat(output.getAll()).contains("tenancy: customer customera pool opened")
                .contains("schema at version " + AppSchemaVersion.latest().getVersion());
        var password = environment.getRequiredProperty("spring.datasource.password");
        assertThat(output.getAll().contains(password)).as("the database password appears in the output").isFalse();
    }

    @Test
    void aDatabaseBehindTheApp_isRefusedWithoutConnectingUntilTheRecheck_thenServedOnceUpdated(CapturedOutput output) {
        var latest = AppSchemaVersion.latest().getVersion();
        var current = customer("customerd");
        assertThatThrownBy(() -> CustomerContext.callAs(current, this::rawConnection))
                .isInstanceOf(CustomerSchemaBehindException.class).isInstanceOf(CustomerNotServedException.class)
                .hasMessageContaining("database at schema version 115, the app needs " + latest);
        assertThat(output.getAll()).contains("tenancy: customer customerd database " + databaseNames.get("customerd")
                + " is at schema version 115, behind the app's " + latest + ": not served");
        assertThat(pools.hasPool("customerd")).isFalse();
        assertThat(firstConnections.get()).as("listeners not told").isZero();

        // Updated, as the migration command would; still refused, without a connection, until the recheck time.
        behindDatabase.flyway("classpath:db/migration").migrate();
        var made = pools.poolsCreated();
        assertThatThrownBy(() -> CustomerContext.callAs(current, this::rawConnection)).isInstanceOf(CustomerSchemaBehindException.class);
        assertThat(pools.poolsCreated()).as("no pool made before the recheck").isEqualTo(made);
        assertThat(as("customera", this::owner)).as("the others are served meanwhile").isEqualTo("customera");

        clock.setInstant(clock.instant().plus(new CustomerPoolProperties().getSchemaRecheck()));
        assertThat(as("customerd", this::owner)).isEqualTo("customerd");
        assertThat(output.getAll()).contains("tenancy: customer customerd pool opened").contains("schema at version " + latest);
    }

    @Test
    void aDatabaseAheadOfTheApp_isServed_withAWarning(CapturedOutput output) {
        assertThat(as("customere", this::owner)).isEqualTo("customere");
        assertThat(output.getAll()).contains("tenancy: customer customere database is at schema version 117, ahead of the app's "
                + AppSchemaVersion.latest().getVersion() + ": served");
    }

    @Test
    void anUnknownCustomer_orAStaleId_isRefused() {
        assertThatThrownBy(() -> CustomerContext.callAs(new CurrentCustomer(999, "nobody"), this::rawConnection))
                .isInstanceOf(UnknownCustomerException.class);
        var stale = new CurrentCustomer(tenantId("customera") + 1000, "customera");
        assertThatThrownBy(() -> CustomerContext.callAs(stale, this::rawConnection))
                .isInstanceOf(UnknownCustomerException.class);
    }

    // --- helpers ---

    private String as(String slug, java.util.function.Supplier<String> work) {
        return CustomerContext.callAs(customer(slug), work);
    }

    private CurrentCustomer customer(String slug) {
        return new CurrentCustomer(tenantId(slug), slug);
    }

    private long tenantId(String slug) {
        return platformJdbc.queryForObject("SELECT id FROM tenants WHERE slug = ?", Long.class, slug);
    }

    private String owner() {
        try (var connection = routing.getConnection(); var st = connection.createStatement();
             var rs = st.executeQuery("SELECT owner FROM marker")) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private java.sql.Connection rawConnection() {
        try {
            return routing.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void register(String slug, String status, String databaseName, String secretPrefix) {
        var id = platformJdbc.queryForObject("INSERT INTO tenants (slug, display_name, status) OUTPUT INSERTED.id VALUES (?, ?, ?)",
                Long.class, slug, slug, status);
        platformJdbc.update("INSERT INTO tenant_hosts (tenant_id, host, is_primary) VALUES (?, ?, 1)", id, slug + ".pos.localhost");
        platformJdbc.update("INSERT INTO tenant_databases (tenant_id, server_host, server_port, database_name, connection_options, "
                        + "login_secret_name, password_secret_name, built_from_baseline) VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                id, serverHost, serverPort, databaseName, options, secretPrefix + "-db-login", secretPrefix + "-db-password");
    }

    /** The test URL's options without databaseName (the location names the database itself). */
    private static String optionsWithoutDatabase(String rest) {
        if (rest == null) {
            return null;
        }
        var kept = new ArrayList<String>();
        for (var part : rest.split(";")) {
            if (!part.isBlank() && !part.toLowerCase().startsWith("databasename=")) {
                kept.add(part.trim());
            }
        }
        return kept.isEmpty() ? null : String.join(";", kept);
    }
}
