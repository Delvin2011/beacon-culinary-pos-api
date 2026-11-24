package com.beaconculinary.api.tenancy;

import com.beaconculinary.api.support.baseline.ThrowawayDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1-1: the platform database's migration (db/platform) on a throwaway database: it applies on an empty database
 * with its own history, and every constraint holds (slug format, unique slug, host format and uniqueness, one primary
 * host per customer, one database row per customer, no credentials in the options, secret-name format). Skipped when
 * the test login can't create databases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformMigrationIntegrationTests {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private Environment environment;

    private ThrowawayDatabase db;

    @BeforeAll
    void createAndMigrate() throws SQLException {
        var url = environment.getRequiredProperty("spring.datasource.url");
        var username = environment.getRequiredProperty("spring.datasource.username");
        var password = environment.getRequiredProperty("spring.datasource.password");
        Assumptions.assumeTrue(ThrowawayDatabase.canCreate(url, username, password), "the test login can't create databases");
        db = ThrowawayDatabase.create(url, username, password, "Platform", "SQL_Latin1_General_CP1_CI_AS");
        var result = db.flyway("classpath:db/platform").migrate();
        assertThat(result.success).isTrue();
    }

    @AfterAll
    void drop() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void theMigrationMakesTheFourTables_withItsOwnHistory() throws SQLException {
        try (var c = db.connect()) {
            assertThat(strings(c, "SELECT name FROM sys.tables ORDER BY name"))
                    .containsExactly("flyway_schema_history", "provisioning_runs", "tenant_databases", "tenant_hosts", "tenants");
            assertThat(strings(c, "SELECT CONCAT(version, ':', type, ':', success) FROM flyway_schema_history"))
                    .containsExactly("1:SQL:1");
        }
    }

    @Test
    void slugs_mustBe3To40LowerCaseLettersDigitsAndHyphens() throws SQLException {
        try (var c = db.connect()) {
            for (var good : List.of("abc", "beacon-culinary", "a1b2", "x".repeat(40), "9to5")) {
                insertTenant(c, good);
            }
            for (var bad : List.of("ab", "Beacon", "beacon_culinary", "-abc", "abc-", "a b c", "trailing ", "caf" + (char) 0xE9)) {
                assertThatThrownBy(() -> insertTenant(c, bad)).as(bad).isInstanceOf(SQLException.class)
                        .hasMessageContaining("CK_tenants_slug");
            }
            // 41 characters don't fit the column at all.
            assertThatThrownBy(() -> insertTenant(c, "x".repeat(41))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("truncated");
        }
    }

    @Test
    void aSlug_belongsToOneCustomer_andTheStatusIsOneOfFive() throws SQLException {
        try (var c = db.connect()) {
            var slug = uniqueSlug();
            insertTenant(c, slug);
            assertThatThrownBy(() -> insertTenant(c, slug)).hasMessageContaining("UQ_tenants_slug");
            for (var status : List.of("PROVISIONING", "ACTIVE", "SUSPENDED", "MIGRATION_FAILED", "ARCHIVED")) {
                execute(c, "UPDATE tenants SET status = '" + status + "' WHERE slug = '" + slug + "'");
            }
            assertThatThrownBy(() -> execute(c, "UPDATE tenants SET status = 'DELETED' WHERE slug = '" + slug + "'"))
                    .hasMessageContaining("CK_tenants_status");
        }
    }

    @Test
    void hosts_areLowerCase_withoutPortOrTrailingDot_andBelongToOneCustomer() throws SQLException {
        try (var c = db.connect()) {
            var a = insertTenant(c, uniqueSlug());
            var b = insertTenant(c, uniqueSlug());
            var host = "h" + SEQUENCE.incrementAndGet() + ".pos.localhost";
            insertHost(c, a, host, false);
            assertThatThrownBy(() -> insertHost(c, b, host, false)).hasMessageContaining("UQ_tenant_hosts_host");
            // An upper-case copy is refused too: by the unique index (the database's collation ignores case) or the
            // lower-case rule, whichever SQL Server checks first.
            assertThatThrownBy(() -> insertHost(c, b, host.toUpperCase(), false)).isInstanceOf(SQLException.class)
                    .message().containsAnyOf("UQ_tenant_hosts_host", "CK_tenant_hosts_host");
            for (var bad : List.of("Shop.pos.localhost", "shop.pos.localhost:443", "shop.pos.localhost.", ".shop.pos",
                    "shop..pos", "sh", "shop pos", "shop_pos.localhost")) {
                assertThatThrownBy(() -> insertHost(c, b, bad, false)).as(bad).hasMessageContaining("CK_tenant_hosts_host");
            }
        }
    }

    @Test
    void aCustomer_hasAtMostOnePrimaryHost() throws SQLException {
        try (var c = db.connect()) {
            var tenant = insertTenant(c, uniqueSlug());
            insertHost(c, tenant, "p" + SEQUENCE.incrementAndGet() + ".pos.localhost", true);
            insertHost(c, tenant, "s" + SEQUENCE.incrementAndGet() + ".pos.localhost", false);
            assertThatThrownBy(() -> insertHost(c, tenant, "q" + SEQUENCE.incrementAndGet() + ".pos.localhost", true))
                    .hasMessageContaining("UX_tenant_hosts_one_primary");
            // Another customer has its own primary.
            insertHost(c, insertTenant(c, uniqueSlug()), "o" + SEQUENCE.incrementAndGet() + ".pos.localhost", true);
        }
    }

    @Test
    void aCustomer_hasOneDatabaseRow_andTwoCustomersNeverShareADatabase() throws SQLException {
        try (var c = db.connect()) {
            var a = insertTenant(c, uniqueSlug());
            var b = insertTenant(c, uniqueSlug());
            var name = "db" + SEQUENCE.incrementAndGet();
            insertDatabase(c, a, name, null, "a-db-login", "a-db-password");
            assertThatThrownBy(() -> insertDatabase(c, a, name + "x", null, "a-db-login", "a-db-password"))
                    .hasMessageContaining("PK_tenant_databases");
            assertThatThrownBy(() -> insertDatabase(c, b, name, null, "b-db-login", "b-db-password"))
                    .hasMessageContaining("UQ_tenant_databases_location");
            assertThat(strings(c, "SELECT CONCAT(server_port, ':', expected_collation, ':', built_from_baseline) FROM tenant_databases WHERE tenant_id = " + a))
                    .containsExactly("1433:SQL_Latin1_General_CP1_CI_AS:1");
        }
    }

    @Test
    void connectionOptions_neverHoldCredentials() throws SQLException {
        try (var c = db.connect()) {
            insertDatabase(c, insertTenant(c, uniqueSlug()), "ok" + SEQUENCE.incrementAndGet(),
                    "encrypt=true;trustServerCertificate=false", "ok-login", "ok-password");
            for (var bad : List.of("password=x", "Password=x", "pwd=x", "user=sa", "userName=sa", "uid=sa", "clientSecret=x")) {
                var tenant = insertTenant(c, uniqueSlug());
                assertThatThrownBy(() -> insertDatabase(c, tenant, "bad" + SEQUENCE.incrementAndGet(), bad, "l", "p"))
                        .as(bad).hasMessageContaining("CK_tenant_databases_connection_options");
            }
        }
    }

    @Test
    void secretNames_followKeyVaultsRule() throws SQLException {
        try (var c = db.connect()) {
            for (var bad : List.of("Beacon-db-password", "beacon_db_password", "-beacon", "beacon-", "beacon--db", "beacon db", "")) {
                var tenant = insertTenant(c, uniqueSlug());
                assertThatThrownBy(() -> insertDatabase(c, tenant, "s" + SEQUENCE.incrementAndGet(), null, "good-login", bad))
                        .as("password secret name [" + bad + "]").hasMessageContaining("CK_tenant_databases_password_secret_name");
                assertThatThrownBy(() -> insertDatabase(c, tenant, "s" + SEQUENCE.incrementAndGet(), null, bad, "good-password"))
                        .as("login secret name [" + bad + "]").hasMessageContaining("CK_tenant_databases_login_secret_name");
            }
        }
    }

    @Test
    void provisioningRuns_keepTheSlugEvenWithoutACustomer_andAKnownOutcome() throws SQLException {
        try (var c = db.connect()) {
            execute(c, "INSERT INTO provisioning_runs (tenant_slug, action, started_at, outcome, message) "
                    + "VALUES ('not-registered', 'REGISTER', SYSUTCDATETIME(), 'REFUSED', 'host in use')");
            assertThatThrownBy(() -> execute(c, "INSERT INTO provisioning_runs (tenant_slug, action, started_at, outcome) "
                    + "VALUES ('x', 'REGISTER', SYSUTCDATETIME(), 'DONE')")).hasMessageContaining("CK_provisioning_runs_outcome");
            assertThatThrownBy(() -> execute(c, "INSERT INTO provisioning_runs (tenant_slug, action, started_at, outcome) "
                    + "VALUES ('x', 'register', SYSUTCDATETIME(), 'SUCCEEDED')")).hasMessageContaining("CK_provisioning_runs_action");
        }
    }

    private static String uniqueSlug() {
        return "t" + SEQUENCE.incrementAndGet() + "-customer";
    }

    private static long insertTenant(Connection c, String slug) throws SQLException {
        try (var st = c.prepareStatement("INSERT INTO tenants (slug, display_name, status) OUTPUT INSERTED.id VALUES (?, ?, 'ACTIVE')")) {
            st.setString(1, slug);
            st.setString(2, "Customer " + slug);
            try (var rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void insertHost(Connection c, long tenant, String host, boolean primary) throws SQLException {
        try (var st = c.prepareStatement("INSERT INTO tenant_hosts (tenant_id, host, is_primary) VALUES (?, ?, ?)")) {
            st.setLong(1, tenant);
            st.setString(2, host);
            st.setBoolean(3, primary);
            st.executeUpdate();
        }
    }

    private static void insertDatabase(Connection c, long tenant, String name, String options, String loginSecret,
                                       String passwordSecret) throws SQLException {
        try (var st = c.prepareStatement("INSERT INTO tenant_databases (tenant_id, server_host, database_name, connection_options, "
                + "login_secret_name, password_secret_name, built_from_baseline) VALUES (?, 'sql', ?, ?, ?, ?, 1)")) {
            st.setLong(1, tenant);
            st.setString(2, name);
            st.setString(3, options);
            st.setString(4, loginSecret);
            st.setString(5, passwordSecret);
            st.executeUpdate();
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static List<String> strings(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            var values = new java.util.ArrayList<String>();
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            return values;
        }
    }
}
