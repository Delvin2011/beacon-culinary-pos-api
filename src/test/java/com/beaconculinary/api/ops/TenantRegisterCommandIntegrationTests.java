package com.beaconculinary.api.ops;

import com.beaconculinary.api.support.baseline.ThrowawayDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-1: TenantRegisterCommand on a throwaway platform database. register creates, updates and is idempotent; a host or
 * database that belongs to another customer is refused; suspend, activate, add-host, remove-host and show; each change
 * records a provisioning run; bad input is a usage error before anything connects; nothing printed holds the platform
 * password.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantRegisterCommandIntegrationTests {
    @Autowired
    private Environment environment;

    private ThrowawayDatabase db;
    private JdbcTemplate jdbc;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeAll
    void createPlatformDatabase() throws SQLException {
        var url = environment.getRequiredProperty("spring.datasource.url");
        var username = environment.getRequiredProperty("spring.datasource.username");
        var password = environment.getRequiredProperty("spring.datasource.password");
        Assumptions.assumeTrue(ThrowawayDatabase.canCreate(url, username, password), "the test login can't create databases");
        db = ThrowawayDatabase.create(url, username, password, "Register", "SQL_Latin1_General_CP1_CI_AS");
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
        assertThat(run("migrate")).isZero();
        jdbc.update("DELETE FROM provisioning_runs");
        jdbc.update("DELETE FROM tenant_databases");
        jdbc.update("DELETE FROM tenant_hosts");
        jdbc.update("DELETE FROM tenants");
    }

    @Test
    void migrate_bringsAnEmptyDatabaseToTheCurrentVersion() {
        assertThat(run("migrate")).isZero();
        assertThat(out()).contains("Platform database at version 2");
    }

    @Test
    void register_createsTheCustomerItsHostsAndItsDatabase_andRecordsARun() {
        assertThat(run(beacon())).isZero();
        assertThat(out()).contains("Done: registered beaconculinary (ACTIVE, 2 host(s))");

        assertThat(jdbc.queryForMap("SELECT slug, display_name, status FROM tenants"))
                .containsEntry("slug", "beaconculinary").containsEntry("display_name", "Beacon Culinary").containsEntry("status", "ACTIVE");
        assertThat(jdbc.queryForList("SELECT CONCAT(host, ':', CAST(is_primary AS int)) FROM tenant_hosts ORDER BY is_primary DESC", String.class))
                .containsExactly("beaconculinary.pos.localhost:1", "pos.beaconculinary.example:0");
        assertThat(jdbc.queryForMap("SELECT server_host, server_port, database_name, connection_options, login_secret_name, "
                + "password_secret_name, built_from_baseline, expected_collation FROM tenant_databases"))
                .containsEntry("server_host", "sql").containsEntry("server_port", 1433).containsEntry("database_name", "beaconculinary")
                .containsEntry("connection_options", "encrypt=true;trustServerCertificate=true")
                .containsEntry("login_secret_name", "beaconculinary-db-login")
                .containsEntry("password_secret_name", "beaconculinary-db-password")
                .containsEntry("built_from_baseline", false).containsEntry("expected_collation", "SQL_Latin1_General_CP1_CI_AS");
        assertThat(runs()).containsExactly("beaconculinary:REGISTER:SUCCEEDED");
    }

    @Test
    void register_again_withTheSameValues_changesNothing_andRecordsUnchanged() {
        run(beacon());
        var before = jdbc.queryForList("SELECT * FROM tenants");
        var hostsBefore = jdbc.queryForList("SELECT * FROM tenant_hosts ORDER BY id");

        assertThat(run(beacon())).isZero();
        assertThat(out()).contains("Unchanged: beaconculinary is already registered with these values");
        assertThat(jdbc.queryForList("SELECT * FROM tenants")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM tenant_hosts ORDER BY id")).isEqualTo(hostsBefore);
        assertThat(runs()).containsExactly("beaconculinary:REGISTER:SUCCEEDED", "beaconculinary:REGISTER:UNCHANGED");
    }

    @Test
    void register_withOtherValues_updatesThem() {
        run(beacon());
        assertThat(run("register", "--slug", "beaconculinary", "--name", "Beacon Culinary (Pty) Ltd",
                "--host", "pos.beaconculinary.example", "--host", "beaconculinary.pos.localhost",
                "--db-server", "sql", "--db-name", "beaconculinary", "--db-options", "encrypt=true;trustServerCertificate=true",
                "--login-secret", "beaconculinary-db-login", "--password-secret", "beaconculinary-db-password-2",
                "--built-from-baseline", "false")).isZero();
        assertThat(out()).contains("Done: updated beaconculinary: name/status, hosts, database");
        assertThat(jdbc.queryForObject("SELECT display_name FROM tenants", String.class)).isEqualTo("Beacon Culinary (Pty) Ltd");
        assertThat(jdbc.queryForObject("SELECT host FROM tenant_hosts WHERE is_primary = 1", String.class)).isEqualTo("pos.beaconculinary.example");
        assertThat(jdbc.queryForObject("SELECT password_secret_name FROM tenant_databases", String.class)).isEqualTo("beaconculinary-db-password-2");
        assertThat(runs()).containsExactly("beaconculinary:REGISTER:SUCCEEDED", "beaconculinary:UPDATE:SUCCEEDED");
    }

    /** T1-4: the migration login's secret names: stored, kept when left out, changed when given, both or neither. */
    @Test
    void register_storesTheMigrationSecretNames_keepsThemWhenLeftOut_andWantsBoth() {
        assertThat(run(withMigrationSecrets(beacon(), "beaconculinary-db-migration-login", "beaconculinary-db-migration-password"))).isZero();
        assertThat(migrationSecrets()).isEqualTo("beaconculinary-db-migration-login/beaconculinary-db-migration-password");

        assertThat(run(beacon())).isZero();
        assertThat(out()).contains("Unchanged: beaconculinary is already registered with these values");
        assertThat(migrationSecrets()).isEqualTo("beaconculinary-db-migration-login/beaconculinary-db-migration-password");

        assertThat(run(withMigrationSecrets(beacon(), "beaconculinary-db-migration-login", "beaconculinary-db-migration-password-2"))).isZero();
        assertThat(out()).contains("Done: updated beaconculinary: database");
        assertThat(migrationSecrets()).isEqualTo("beaconculinary-db-migration-login/beaconculinary-db-migration-password-2");

        var onlyOne = java.util.Arrays.copyOf(beacon(), beacon().length + 2);
        onlyOne[onlyOne.length - 2] = "--migration-login-secret";
        onlyOne[onlyOne.length - 1] = "beaconculinary-db-migration-login";
        assertThat(run(onlyOne)).isEqualTo(TenantRegisterCommand.USAGE);
        assertThat(err()).contains("--migration-login-secret and --migration-password-secret go together.");
        assertThat(run(withMigrationSecrets(beacon(), "beaconculinary-db-migration-login", "Not-A-Name!"))).isEqualTo(TenantRegisterCommand.USAGE);
        assertThat(err()).contains("--migration-password-secret must be a secret NAME");

        assertThat(run("show", "--slug", "beaconculinary")).isZero();
        assertThat(out()).contains("migration login beaconculinary-db-migration-login, password beaconculinary-db-migration-password-2  (names only)");
    }

    @Test
    void aHostOrADatabaseOfAnotherCustomer_isRefused_andTheRefusalRecorded() {
        run(beacon());
        assertThat(run("register", "--slug", "secondcafe", "--name", "Second Cafe", "--host", "BeaconCulinary.pos.localhost:443",
                "--db-server", "sql", "--db-name", "secondcafe", "--login-secret", "secondcafe-db-login",
                "--password-secret", "secondcafe-db-password", "--built-from-baseline", "true")).isEqualTo(TenantRegisterCommand.REFUSED);
        assertThat(err()).contains("Refused: host beaconculinary.pos.localhost belongs to customer beaconculinary");

        assertThat(run("register", "--slug", "secondcafe", "--name", "Second Cafe", "--host", "secondcafe.pos.localhost",
                "--db-server", "SQL", "--db-name", "beaconculinary", "--login-secret", "secondcafe-db-login",
                "--password-secret", "secondcafe-db-password", "--built-from-baseline", "true")).isEqualTo(TenantRegisterCommand.REFUSED);
        assertThat(err()).contains("belongs to customer beaconculinary");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE slug = 'secondcafe'", Integer.class)).isZero();
        assertThat(runs()).containsExactly("beaconculinary:REGISTER:SUCCEEDED", "secondcafe:REGISTER:REFUSED", "secondcafe:REGISTER:REFUSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provisioning_runs WHERE outcome = 'REFUSED' AND tenant_id IS NULL", Integer.class)).isEqualTo(2);
    }

    @Test
    void suspendActivateAndHosts() {
        run(beacon());
        assertThat(run("suspend", "--slug", "beaconculinary")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM tenants", String.class)).isEqualTo("SUSPENDED");
        assertThat(run("suspend", "--slug", "beaconculinary")).isZero();
        assertThat(out()).contains("Unchanged: beaconculinary is already SUSPENDED");
        assertThat(run("activate", "--slug", "beaconculinary")).isZero();
        assertThat(run("activate", "--slug", "nobody")).isEqualTo(TenantRegisterCommand.REFUSED);

        assertThat(run("add-host", "--slug", "beaconculinary", "--host", "Till.Beaconculinary.example.")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_hosts WHERE host = 'till.beaconculinary.example' AND is_primary = 0", Integer.class)).isOne();
        assertThat(run("add-host", "--slug", "beaconculinary", "--host", "till.beaconculinary.example", "--primary")).isZero();
        assertThat(jdbc.queryForObject("SELECT host FROM tenant_hosts WHERE is_primary = 1", String.class)).isEqualTo("till.beaconculinary.example");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_hosts WHERE is_primary = 1", Integer.class)).isOne();

        assertThat(run("remove-host", "--slug", "beaconculinary", "--host", "till.beaconculinary.example")).isEqualTo(TenantRegisterCommand.REFUSED);
        assertThat(err()).contains("make another host primary first");
        assertThat(run("remove-host", "--slug", "beaconculinary", "--host", "pos.beaconculinary.example")).isZero();
        assertThat(run("remove-host", "--slug", "beaconculinary", "--host", "nowhere.example")).isEqualTo(TenantRegisterCommand.REFUSED);

        assertThat(runs()).containsExactly("beaconculinary:REGISTER:SUCCEEDED", "beaconculinary:SUSPEND:SUCCEEDED",
                "beaconculinary:SUSPEND:UNCHANGED", "beaconculinary:ACTIVATE:SUCCEEDED", "nobody:ACTIVATE:REFUSED",
                "beaconculinary:ADD_HOST:SUCCEEDED", "beaconculinary:ADD_HOST:SUCCEEDED", "beaconculinary:REMOVE_HOST:REFUSED",
                "beaconculinary:REMOVE_HOST:SUCCEEDED", "beaconculinary:REMOVE_HOST:REFUSED");
    }

    @Test
    void show_listsCustomersWithSecretNamesOnly() {
        run(beacon());
        assertThat(run("show")).isZero();
        assertThat(out()).contains("beaconculinary  ACTIVE  \"Beacon Culinary\"")
                .contains("host      beaconculinary.pos.localhost  (primary)")
                .contains("database  sql:1433/beaconculinary")
                .contains("secrets   login beaconculinary-db-login, password beaconculinary-db-password  (names only)")
                .contains("built     with full history, collation SQL_Latin1_General_CP1_CI_AS");
        assertThat(run("show", "--slug", "nobody")).isEqualTo(TenantRegisterCommand.REFUSED);
    }

    @Test
    void badInput_isAUsageError_andChangesNothing() {
        var cases = Map.of(
                "slug", List.of("register", "--slug", "Beacon", "--name", "x", "--host", "a.example", "--db-server", "sql", "--db-name", "d",
                        "--login-secret", "a-login", "--password-secret", "a-password", "--built-from-baseline", "true"),
                "secret value as a name", List.of("register", "--slug", "abc", "--name", "x", "--host", "a.example", "--db-server", "sql", "--db-name", "d",
                        "--login-secret", "a-login", "--password-secret", "Pa55word!", "--built-from-baseline", "true"),
                "credentials in options", List.of("register", "--slug", "abc", "--name", "x", "--host", "a.example", "--db-server", "sql", "--db-name", "d",
                        "--db-options", "encrypt=true;password=x", "--login-secret", "a-login", "--password-secret", "a-password", "--built-from-baseline", "true"),
                "host with path", List.of("add-host", "--slug", "abc", "--host", "a.example/x"),
                "unknown action", List.of("delete", "--slug", "abc"),
                "unknown option", List.of("suspend", "--slug", "abc", "--force", "yes"));
        cases.forEach((label, args) -> assertThat(run(args.toArray(String[]::new))).as(label).isEqualTo(TenantRegisterCommand.USAGE));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provisioning_runs", Integer.class)).isZero();
    }

    @Test
    void nothingPrinted_holdsThePlatformPassword() {
        run(beacon());
        run(beacon());
        run("show");
        run("register", "--slug", "secondcafe", "--name", "x", "--host", "beaconculinary.pos.localhost", "--db-server", "sql",
                "--db-name", "s", "--login-secret", "s-login", "--password-secret", "s-password", "--built-from-baseline", "true");
        var everything = String.join("\n", printed);
        // A boolean, so a failure can't print the password either.
        assertThat(everything.contains(db.password())).as("the platform password appears in the output").isFalse();
        assertThat(jdbc.queryForList("SELECT ISNULL(message, '') FROM provisioning_runs", String.class).stream()
                .anyMatch(m -> m.contains(db.password()))).as("the password appears in a run message").isFalse();
    }

    private final List<String> printed = new ArrayList<>();

    private int run(String... args) {
        out.reset();
        err.reset();
        var code = new TenantRegisterCommand(db.url(), db.username(), db.password(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Clock.systemUTC(), "test-operator").run(args);
        printed.add(out());
        printed.add(err());
        return code;
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private static String[] beacon() {
        return new String[]{"register", "--slug", "beaconculinary", "--name", "Beacon Culinary",
                "--host", "beaconculinary.pos.localhost", "--host", "POS.BeaconCulinary.example",
                "--db-server", "sql", "--db-name", "beaconculinary", "--db-options", "encrypt=true;trustServerCertificate=true",
                "--login-secret", "beaconculinary-db-login", "--password-secret", "beaconculinary-db-password",
                "--built-from-baseline", "false"};
    }

    private static String[] withMigrationSecrets(String[] args, String login, String password) {
        var all = java.util.Arrays.copyOf(args, args.length + 4);
        all[args.length] = "--migration-login-secret";
        all[args.length + 1] = login;
        all[args.length + 2] = "--migration-password-secret";
        all[args.length + 3] = password;
        return all;
    }

    private String migrationSecrets() {
        return jdbc.queryForObject("SELECT CONCAT(migration_login_secret_name, '/', migration_password_secret_name) FROM tenant_databases",
                String.class);
    }

    private List<String> runs() {
        return jdbc.queryForList("SELECT CONCAT(tenant_slug, ':', action, ':', outcome) FROM provisioning_runs ORDER BY id", String.class);
    }
}
