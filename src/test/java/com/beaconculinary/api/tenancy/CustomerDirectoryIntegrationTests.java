package com.beaconculinary.api.tenancy;

import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.baseline.ThrowawayDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-1: the customer directory on a throwaway platform database. Lookup by host ignores case, the port and a trailing
 * dot; an unknown host finds nothing; a suspended customer is found with its status and left out of the active list;
 * the snapshot is used for the TTL, read again after it, and at once after refresh(); when a read fails, the last good
 * snapshot answers (with one warning) until a read succeeds again. No secret values: only names. T1-6: the time zone
 * is read as stored, null when not set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerDirectoryIntegrationTests {
    private static final Duration TTL = Duration.ofSeconds(30);

    @Autowired
    private Environment environment;

    private ThrowawayDatabase db;
    private JdbcTemplate jdbc;
    private MutableClock clock;

    @BeforeAll
    void createPlatformDatabase() throws SQLException {
        var url = environment.getRequiredProperty("spring.datasource.url");
        var username = environment.getRequiredProperty("spring.datasource.username");
        var password = environment.getRequiredProperty("spring.datasource.password");
        Assumptions.assumeTrue(ThrowawayDatabase.canCreate(url, username, password), "the test login can't create databases");
        db = ThrowawayDatabase.create(url, username, password, "Directory", "SQL_Latin1_General_CP1_CI_AS");
        db.flyway(PlatformDatabase.LOCATION).migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(db.url(), db.username(), db.password()));
    }

    @AfterAll
    void drop() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void freshData() {
        jdbc.update("DELETE FROM provisioning_runs");
        jdbc.update("DELETE FROM tenant_databases");
        jdbc.update("DELETE FROM tenant_hosts");
        jdbc.update("DELETE FROM tenants");
        clock = new MutableClock(Clock.fixed(Instant.parse("2026-11-24T08:00:00Z"), ZoneOffset.UTC));
        var beacon = tenant("beaconculinary", "Beacon Culinary", "ACTIVE");
        host(beacon, "beaconculinary.pos.localhost", true);
        host(beacon, "pos.beaconculinary.co.za", false);
        database(beacon, "beaconculinary", false);
        var test = tenant("testcustomer", "Test Customer", "SUSPENDED");
        host(test, "testcustomer.pos.localhost", true);
        database(test, "testcustomer", true);
    }

    @Test
    void findByHost_ignoresCaseThePortAndATrailingDot() {
        var directory = directory();
        for (var raw : new String[]{"beaconculinary.pos.localhost", "BeaconCulinary.POS.localhost:443",
                "beaconculinary.pos.localhost.", "POS.BeaconCulinary.co.za.:8443"}) {
            assertThat(directory.findByHost(raw)).as(raw).hasValueSatisfying(c -> assertThat(c.slug()).isEqualTo("beaconculinary"));
        }
        var beacon = directory.findByHost("beaconculinary.pos.localhost").orElseThrow();
        assertThat(beacon.displayName()).isEqualTo("Beacon Culinary");
        assertThat(beacon.hosts()).containsExactly("beaconculinary.pos.localhost", "pos.beaconculinary.co.za");
        assertThat(beacon.primaryHost()).isEqualTo("beaconculinary.pos.localhost");
        var location = beacon.database();
        assertThat(location.serverHost()).isEqualTo("sql");
        assertThat(location.serverPort()).isEqualTo(1433);
        assertThat(location.databaseName()).isEqualTo("beaconculinary");
        assertThat(location.loginSecretName()).isEqualTo("beaconculinary-db-login");
        assertThat(location.passwordSecretName()).isEqualTo("beaconculinary-db-password");
        assertThat(location.builtFromBaseline()).isFalse();
        assertThat(location.expectedCollation()).isEqualTo("SQL_Latin1_General_CP1_CI_AS");
    }

    @Test
    void anUnknownOrMalformedHost_findsNothing() {
        var directory = directory();
        assertThat(directory.findByHost("nobody.pos.localhost")).isEmpty();
        assertThat(directory.findByHost("[::1]:443")).isEmpty();
        assertThat(directory.findByHost(null)).isEmpty();
        assertThat(directory.findBySlug("nobody")).isEmpty();
    }

    @Test
    void aSuspendedCustomer_isFoundWithItsStatus_butIsNotActive() {
        var directory = directory();
        var test = directory.findByHost("testcustomer.pos.localhost").orElseThrow();
        assertThat(test.status()).isEqualTo(CustomerStatus.SUSPENDED);
        assertThat(test.isActive()).isFalse();
        assertThat(test.database().builtFromBaseline()).isTrue();
        assertThat(directory.findBySlug("TestCustomer")).hasValueSatisfying(c -> assertThat(c.status()).isEqualTo(CustomerStatus.SUSPENDED));
        assertThat(directory.listActive()).extracting(Customer::slug).containsExactly("beaconculinary");
        assertThat(directory.listAll()).extracting(Customer::slug).containsExactly("beaconculinary", "testcustomer");
    }

    @Test
    void theSnapshotIsUsedForTheTtl_thenReadAgain_andRefreshReadsAtOnce() {
        var directory = directory();
        jdbc.update("UPDATE tenants SET status = 'ACTIVE', display_name = 'Test Customer Renamed' WHERE slug = 'testcustomer'");
        var newer = tenant("thirdcafe", "Third Cafe", "ACTIVE");
        host(newer, "thirdcafe.pos.localhost", true);

        clock.setInstant(clock.instant().plus(TTL).minusSeconds(1));
        assertThat(directory.findByHost("thirdcafe.pos.localhost")).as("within the TTL").isEmpty();
        assertThat(directory.findBySlug("testcustomer").orElseThrow().status()).isEqualTo(CustomerStatus.SUSPENDED);

        clock.setInstant(clock.instant().plusSeconds(2));
        assertThat(directory.findByHost("thirdcafe.pos.localhost")).as("after the TTL").isPresent();
        assertThat(directory.findBySlug("testcustomer").orElseThrow().displayName()).isEqualTo("Test Customer Renamed");
        assertThat(directory.listActive()).extracting(Customer::slug).containsExactly("beaconculinary", "testcustomer", "thirdcafe");

        jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE slug = 'thirdcafe'");
        directory.refresh();
        assertThat(directory.findBySlug("thirdcafe").orElseThrow().status()).as("after refresh()").isEqualTo(CustomerStatus.SUSPENDED);
    }

    @Test
    void whenAReadFails_theLastGoodSnapshotAnswers_withOneWarning_andTheNextGoodReadRecovers(CapturedOutput output) {
        var platformDown = new AtomicBoolean(false);
        var flaky = new DelegatingDataSource(jdbc.getDataSource()) {
            @Override
            public Connection getConnection() throws SQLException {
                if (platformDown.get()) {
                    throw new SQLException("platform database unreachable (test)");
                }
                return super.getConnection();
            }
        };
        var directory = new CustomerDirectory(new JdbcTemplate(flaky), clock, TTL);
        var third = tenant("thirdcafe", "Third Cafe", "ACTIVE");
        host(third, "thirdcafe.pos.localhost", true);

        // The TTL is over and the platform database is down: the last good snapshot answers.
        platformDown.set(true);
        clock.setInstant(clock.instant().plus(TTL).plusSeconds(1));
        assertThat(directory.findByHost("beaconculinary.pos.localhost")).as("from the last good snapshot").isPresent();
        assertThat(directory.findByHost("thirdcafe.pos.localhost")).as("added after the last good read").isEmpty();
        assertThat(directory.listActive()).extracting(Customer::slug).containsExactly("beaconculinary");
        assertThat(warnings(output)).as("one warning for the failed read").isEqualTo(1);

        // Still down, within the next TTL: no new read, so no new warning.
        clock.setInstant(clock.instant().plus(TTL).minusSeconds(1));
        assertThat(directory.findBySlug("testcustomer")).isPresent();
        assertThat(warnings(output)).isEqualTo(1);

        // Back up: the next read after that TTL succeeds and sees the change.
        platformDown.set(false);
        clock.setInstant(clock.instant().plusSeconds(2));
        assertThat(directory.findByHost("thirdcafe.pos.localhost")).as("after recovery").isPresent();
        assertThat(directory.listActive()).extracting(Customer::slug).containsExactly("beaconculinary", "thirdcafe");
        assertThat(warnings(output)).as("no warning once reads succeed").isEqualTo(1);
    }

    private static int warnings(CapturedOutput output) {
        return output.getAll().split("Customer directory: reading the platform database failed", -1).length - 1;
    }

    @Test
    void aCustomerWithoutADatabaseYet_isListedWithoutOne() {
        tenant("newcafe", "New Cafe", "PROVISIONING");
        var directory = directory();
        assertThat(directory.findBySlug("newcafe")).hasValueSatisfying(c -> {
            assertThat(c.database()).isNull();
            assertThat(c.hosts()).isEmpty();
            assertThat(c.primaryHost()).isNull();
        });
    }

    @Test
    void theTimeZone_isReadAsStored_andNullWhenNotSet() {
        jdbc.update("UPDATE tenants SET time_zone = 'America/New_York' WHERE slug = 'beaconculinary'");
        var directory = directory();
        assertThat(directory.findBySlug("beaconculinary")).hasValueSatisfying(c -> assertThat(c.timeZone()).isEqualTo("America/New_York"));
        assertThat(directory.findBySlug("testcustomer")).hasValueSatisfying(c -> assertThat(c.timeZone()).isNull());
    }

    private CustomerDirectory directory() {
        return new CustomerDirectory(jdbc, clock, TTL);
    }

    private long tenant(String slug, String name, String status) {
        return jdbc.queryForObject("INSERT INTO tenants (slug, display_name, status) OUTPUT INSERTED.id VALUES (?, ?, ?)",
                Long.class, slug, name, status);
    }

    private void host(long tenant, String host, boolean primary) {
        jdbc.update("INSERT INTO tenant_hosts (tenant_id, host, is_primary) VALUES (?, ?, ?)", tenant, host, primary);
    }

    private void database(long tenant, String name, boolean fromBaseline) {
        jdbc.update("INSERT INTO tenant_databases (tenant_id, server_host, database_name, connection_options, login_secret_name, "
                + "password_secret_name, built_from_baseline) VALUES (?, 'sql', ?, 'encrypt=true', ?, ?, ?)",
                tenant, name, name + "-db-login", name + "-db-password", fromBaseline);
    }
}
