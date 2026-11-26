package com.beaconculinary.api.ops;

import com.beaconculinary.api.support.baseline.ThrowawayDatabase;
import com.beaconculinary.api.tenancy.PlatformDatabase;
import com.beaconculinary.api.tenancy.secrets.MissingSecretException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-4: MigrateCustomersCommand on throwaway databases (a platform database and customer databases on the test server).
 * A test-only migration (V117, src/test/resources/db/t1-4-probe) comes after the newest real one, so an update is real,
 * and fails where its table already exists. Full-history and baseline-built databases are updated, a second run does
 * nothing, a failure at one customer doesn't stop the next and a retry brings it back, --customer and --dry-run, which
 * statuses are taken, the collation check, two copies at once, and nothing secret in the output.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrateCustomersCommandIntegrationTests {
    private static final String PROBE = "classpath:db/t1-4-probe";
    private static final String COLLATION = "SQL_Latin1_General_CP1_CI_AS";
    private static final Pattern SERVER = Pattern.compile("^jdbc:sqlserver://([A-Za-z0-9.-]+)(?::(\\d+))?(;.*)?$");

    @Autowired
    private Environment environment;

    private String url;
    private String username;
    private String password;
    private String host;
    private int port;
    private String options;
    private ThrowawayDatabase platform;
    private JdbcTemplate platformJdbc;
    private final List<ThrowawayDatabase> made = new ArrayList<>();
    private final Map<String, ThrowawayDatabase> bySlug = new ConcurrentHashMap<>();
    private final Map<String, String> secretValues = new ConcurrentHashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeAll
    void createPlatform() throws SQLException {
        url = environment.getRequiredProperty("spring.datasource.url");
        username = environment.getRequiredProperty("spring.datasource.username");
        password = environment.getRequiredProperty("spring.datasource.password");
        Assumptions.assumeTrue(ThrowawayDatabase.canCreate(url, username, password), "the test login can't create databases");
        var server = SERVER.matcher(url);
        Assumptions.assumeTrue(server.matches(), "the test database URL names no plain host and port");
        host = server.group(1);
        port = server.group(2) == null ? 1433 : Integer.parseInt(server.group(2));
        options = optionsOf(server.group(3));
        platform = ThrowawayDatabase.create(url, username, password, "MigPlatform", COLLATION);
        platform.flyway(PlatformDatabase.LOCATION).migrate();
        platformJdbc = jdbc(platform);
    }

    @AfterAll
    void dropPlatform() throws SQLException {
        if (platform != null) {
            platform.close();
        }
    }

    @BeforeEach
    void freshPlatform() {
        platformJdbc.update("DELETE FROM provisioning_runs");
        platformJdbc.update("DELETE FROM tenant_databases");
        platformJdbc.update("DELETE FROM tenant_hosts");
        platformJdbc.update("DELETE FROM tenants");
        secretValues.clear();
        bySlug.clear();
    }

    @AfterEach
    void dropCustomers() throws SQLException {
        for (var db : made) {
            db.close();
        }
        made.clear();
    }

    @Test
    void fullHistoryAndBaselineDatabases_areUpdatedToTheLatest_andASecondRunDoesNothing() {
        register("full-cafe", "ACTIVE", false, true);
        register("base-cafe", "ACTIVE", true, true);

        assertThat(run()).as(out() + err()).isEqualTo(MigrateCustomersCommand.OK);
        assertThat(out()).containsPattern("base-cafe\\s+116\\s+117\\s+SUCCEEDED").containsPattern("full-cafe\\s+none\\s+117\\s+SUCCEEDED")
                .contains("Done.");
        assertThat(history("full-cafe")).startsWith("1:SQL").contains("116:SQL", "117:SQL").noneMatch(v -> v.endsWith("SQL_BASELINE"));
        assertThat(history("base-cafe")).containsExactly("116:SQL_BASELINE", "117:SQL");
        assertThat(lastVersion("full-cafe")).isEqualTo("117");
        assertThat(lastVersion("base-cafe")).isEqualTo("117");
        assertThat(runs()).containsExactly("base-cafe:MIGRATE:SUCCEEDED", "full-cafe:MIGRATE:SUCCEEDED");
        var fullHistory = history("full-cafe");

        assertThat(run()).isEqualTo(MigrateCustomersCommand.OK);
        assertThat(out()).containsPattern("base-cafe\\s+117\\s+117\\s+UNCHANGED").containsPattern("full-cafe\\s+117\\s+117\\s+UNCHANGED");
        assertThat(history("full-cafe")).isEqualTo(fullHistory);
        assertThat(history("base-cafe")).containsExactly("116:SQL_BASELINE", "117:SQL");
        assertThat(runs()).endsWith("base-cafe:MIGRATE:UNCHANGED", "full-cafe:MIGRATE:UNCHANGED");
        assertNoSecrets();
    }

    @Test
    void aFailureAtOneCustomer_doesNotStopTheNext_andARetryBringsItBack() {
        register("a-cafe", "ACTIVE", true, true);
        register("b-cafe", "ACTIVE", true, true);
        customer("a-cafe").execute("CREATE TABLE [t1_4_probe] ([id] INT NOT NULL)");

        assertThat(run()).isEqualTo(MigrateCustomersCommand.FAILED);
        assertThat(out()).containsPattern("a-cafe\\s+116\\s+-\\s+FAILED").containsPattern("b-cafe\\s+116\\s+117\\s+SUCCEEDED")
                .contains("a-cafe: ").contains("1 customer(s) not updated");
        assertThat(status("a-cafe")).isEqualTo("MIGRATION_FAILED");
        assertThat(status("b-cafe")).isEqualTo("ACTIVE");
        assertThat(lastVersion("a-cafe")).isNull();
        assertThat(lastVersion("b-cafe")).isEqualTo("117");
        assertThat(runs()).containsExactly("a-cafe:MIGRATE:FAILED", "b-cafe:MIGRATE:SUCCEEDED");
        assertThat(runMessage("a-cafe")).contains("t1_4_probe").contains("ACTIVE -> MIGRATION_FAILED");
        assertThat(history("a-cafe")).containsExactly("116:SQL_BASELINE");

        customer("a-cafe").execute("DROP TABLE [t1_4_probe]");
        assertThat(run()).as(out()).isEqualTo(MigrateCustomersCommand.OK);
        assertThat(out()).containsPattern("a-cafe\\s+116\\s+117\\s+SUCCEEDED").containsPattern("b-cafe\\s+117\\s+117\\s+UNCHANGED");
        assertThat(status("a-cafe")).isEqualTo("ACTIVE");
        assertThat(runMessage("a-cafe")).contains("MIGRATION_FAILED -> ACTIVE");
        assertNoSecrets();
    }

    @Test
    void customerOption_takesOnlyThatOne_andDryRunWritesNothing() {
        register("x-cafe", "ACTIVE", true, true);
        register("y-cafe", "ACTIVE", true, true);

        assertThat(run("--customer", "y-cafe", "--dry-run")).isEqualTo(MigrateCustomersCommand.OK);
        assertThat(out()).contains("Dry run: nothing is changed.").containsPattern("y-cafe\\s+116\\s+117\\s+WOULD APPLY")
                .doesNotContain("x-cafe");
        assertThat(runs()).isEmpty();
        assertThat(history("y-cafe")).containsExactly("116:SQL_BASELINE");
        assertThat(lastVersion("y-cafe")).isNull();

        assertThat(run("--customer", "y-cafe")).isEqualTo(MigrateCustomersCommand.OK);
        assertThat(history("y-cafe")).containsExactly("116:SQL_BASELINE", "117:SQL");
        assertThat(history("x-cafe")).containsExactly("116:SQL_BASELINE");
        assertThat(runs()).containsExactly("y-cafe:MIGRATE:SUCCEEDED");

        assertThat(run("--dry-run")).isEqualTo(MigrateCustomersCommand.OK);
        assertThat(out()).containsPattern("x-cafe\\s+116\\s+117\\s+WOULD APPLY").containsPattern("y-cafe\\s+117\\s+117\\s+UP TO DATE");
        assertThat(runs()).containsExactly("y-cafe:MIGRATE:SUCCEEDED");

        assertThat(run("--customer", "nobody")).isEqualTo(MigrateCustomersCommand.REFUSED);
        assertThat(err()).contains("Refused: there is no customer nobody.");
        assertThat(run("--customer", "Not_A_Slug")).isEqualTo(MigrateCustomersCommand.USAGE);
        assertThat(run("--everything")).isEqualTo(MigrateCustomersCommand.USAGE);
        assertThat(err()).contains("Unknown option: --everything");
    }

    @Test
    void theDefaultRun_takesActiveAndMigrationFailed_customerTakesSuspended_andArchivedOrNoLoginIsRefused() {
        register("m-cafe", "MIGRATION_FAILED", true, true);
        register("n-cafe", "ACTIVE", true, false);
        register("p-cafe", "PROVISIONING", true, true);
        register("r-cafe", "ARCHIVED", true, true);
        register("s-cafe", "SUSPENDED", true, true);

        assertThat(run()).isEqualTo(MigrateCustomersCommand.FAILED);
        assertThat(out()).containsPattern("m-cafe\\s+116\\s+117\\s+SUCCEEDED").containsPattern("n-cafe\\s+-\\s+-\\s+REFUSED")
                .contains("n-cafe: no migration login registered").doesNotContain("p-cafe").doesNotContain("r-cafe").doesNotContain("s-cafe");
        assertThat(status("m-cafe")).isEqualTo("ACTIVE");
        assertThat(status("n-cafe")).as("refused: nothing changed").isEqualTo("ACTIVE");
        assertThat(runs()).containsExactly("m-cafe:MIGRATE:SUCCEEDED", "n-cafe:MIGRATE:REFUSED");

        assertThat(run("--customer", "s-cafe")).isEqualTo(MigrateCustomersCommand.OK);
        assertThat(status("s-cafe")).as("left as it is").isEqualTo("SUSPENDED");
        assertThat(history("s-cafe")).containsExactly("116:SQL_BASELINE", "117:SQL");
        assertThat(run("--customer", "p-cafe")).isEqualTo(MigrateCustomersCommand.OK);
        assertThat(status("p-cafe")).isEqualTo("PROVISIONING");

        assertThat(run("--customer", "r-cafe")).isEqualTo(MigrateCustomersCommand.REFUSED);
        assertThat(err()).contains("r-cafe is ARCHIVED");
        assertThat(history("r-cafe")).containsExactly("116:SQL_BASELINE");

        secretValues.remove("m-cafe-db-migration-password");
        assertThat(run("--customer", "m-cafe")).isEqualTo(MigrateCustomersCommand.FAILED);
        assertThat(out()).containsPattern("m-cafe\\s+-\\s+-\\s+REFUSED").contains("Secret 'm-cafe-db-migration-password' is not set");
        assertThat(status("m-cafe")).as("a missing secret changes nothing").isEqualTo("ACTIVE");
    }

    @Test
    void aDatabaseWithAnotherCollation_isNotMigrated_andTheCustomerFails() {
        register("c-cafe", "ACTIVE", true, true, "Latin1_General_CI_AS", false);

        assertThat(run()).isEqualTo(MigrateCustomersCommand.FAILED);
        assertThat(out()).contains("c-cafe: the database's collation is Latin1_General_CI_AS, expected " + COLLATION + "; not migrated");
        assertThat(status("c-cafe")).isEqualTo("MIGRATION_FAILED");
        assertThat(customer("c-cafe").queryForObject("SELECT COUNT(*) FROM sys.tables", Integer.class)).as("nothing created").isZero();
    }

    /**
     * Flyway takes its lock (sp_getapplock) for each migration and rechecks what is pending under it: two copies take
     * turns, each migration is applied by exactly one of them, and the other waits and skips it.
     */
    @Test
    void twoCopiesAtOnce_takeTurnsOnFlywaysLock_andApplyEachMigrationOnce() throws Exception {
        register("twin-cafe", "ACTIVE", false, true);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return command(new ByteArrayOutputStream()).run(new String[0]);
            });
            var second = pool.submit(() -> {
                start.await();
                return command(new ByteArrayOutputStream()).run(new String[0]);
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.MINUTES)).isEqualTo(MigrateCustomersCommand.OK);
            assertThat(second.get(10, TimeUnit.MINUTES)).isEqualTo(MigrateCustomersCommand.OK);
        } finally {
            pool.shutdownNow();
        }
        var messages = platformJdbc.queryForList("SELECT CONCAT(outcome, ':', message) FROM provisioning_runs ORDER BY id", String.class);
        assertThat(messages).as("both runs").hasSize(2).allMatch(m -> m.startsWith("SUCCEEDED:") || m.startsWith("UNCHANGED:"));
        var db = customer("twin-cafe");
        var versions = db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL", Integer.class);
        assertThat(db.queryForObject("SELECT COUNT(DISTINCT version) FROM flyway_schema_history WHERE version IS NOT NULL", Integer.class))
                .as("each migration recorded once").isEqualTo(versions);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class)).isZero();
        var applied = messages.stream().mapToInt(m -> Integer.parseInt(m.replaceAll("^\\w+:(\\d+) migration.*$", "$1"))).sum();
        assertThat(applied).as("together the two copies applied every migration once: " + messages).isEqualTo(versions);
        assertThat(lastVersion("twin-cafe")).isEqualTo("117");
        assertThat(status("twin-cafe")).isEqualTo("ACTIVE");
    }

    @Test
    void aFailedLogin_printsAndStoresNoSecretValue() {
        register("w-cafe", "ACTIVE", true, true);
        var wrong = "Wr0ng-Passw0rd-T14!";
        secretValues.put("w-cafe-db-migration-password", wrong);

        assertThat(run()).isEqualTo(MigrateCustomersCommand.FAILED);
        assertThat(status("w-cafe")).isEqualTo("MIGRATION_FAILED");
        var printed = out() + err() + runMessage("w-cafe");
        assertThat(printed).contains("w-cafe: ").doesNotContain(wrong).doesNotContain(password).doesNotContain("'" + username + "'")
                .doesNotContain("databaseName");
        assertNoSecrets();
    }

    // --- helpers ---

    /** A customer database on the test server, registered with its secrets (the migration login is the suite's). */
    private void register(String slug, String status, boolean fromBaseline, boolean migrationLogin) {
        register(slug, status, fromBaseline, migrationLogin, COLLATION, true);
    }

    private void register(String slug, String status, boolean fromBaseline, boolean migrationLogin, String collation, boolean build) {
        ThrowawayDatabase db;
        try {
            db = ThrowawayDatabase.create(url, username, password, "Mig" + slug.replace("-", ""), collation);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        made.add(db);
        bySlug.put(slug, db);
        if (fromBaseline && build) {
            db.flyway("classpath:db/migration", "classpath:db/baseline").migrate();
        }
        var id = platformJdbc.queryForObject("INSERT INTO tenants (slug, display_name, status) OUTPUT INSERTED.id VALUES (?, ?, ?)",
                Long.class, slug, slug, status);
        platformJdbc.update("INSERT INTO tenant_hosts (tenant_id, host, is_primary) VALUES (?, ?, 1)", id, slug + ".pos.localhost");
        platformJdbc.update("INSERT INTO tenant_databases (tenant_id, server_host, server_port, database_name, connection_options, "
                        + "login_secret_name, password_secret_name, built_from_baseline, migration_login_secret_name, "
                        + "migration_password_secret_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, host, port, db.name(), options, slug + "-db-login", slug + "-db-password", fromBaseline,
                migrationLogin ? slug + "-db-migration-login" : null, migrationLogin ? slug + "-db-migration-password" : null);
        if (migrationLogin) {
            secretValues.put(slug + "-db-migration-login", username);
            secretValues.put(slug + "-db-migration-password", password);
        }
    }

    private MigrateCustomersCommand command(ByteArrayOutputStream sink) {
        var print = new PrintStream(sink, true, StandardCharsets.UTF_8);
        return command(print, print);
    }

    private MigrateCustomersCommand command(PrintStream print, PrintStream printErr) {
        return new MigrateCustomersCommand(platform.url(), platform.username(), platform.password(), name -> {
            var value = secretValues.get(name);
            if (value == null) {
                throw new MissingSecretException(name, "the test's secrets");
            }
            return value;
        }, print, printErr, Clock.systemUTC(), "test-operator", List.of(PROBE));
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        return command(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
    }

    private void assertNoSecrets() {
        assertThat(out() + err()).doesNotContain(password);
        assertThat(platformJdbc.queryForList("SELECT ISNULL(message, '') FROM provisioning_runs", String.class))
                .noneMatch(m -> m.contains(password));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private JdbcTemplate customer(String slug) {
        return jdbc(bySlug.get(slug));
    }

    private List<String> history(String slug) {
        var db = customer(slug);
        if (db.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE name = 'flyway_schema_history'", Integer.class) == 0) {
            return List.of();
        }
        return db.queryForList("SELECT CONCAT(version, ':', type) FROM flyway_schema_history WHERE version IS NOT NULL "
                + "ORDER BY installed_rank", String.class);
    }

    private String status(String slug) {
        return platformJdbc.queryForObject("SELECT status FROM tenants WHERE slug = ?", String.class, slug);
    }

    private String lastVersion(String slug) {
        return platformJdbc.queryForObject("SELECT d.last_migrated_version FROM tenant_databases d JOIN tenants t ON t.id = d.tenant_id "
                + "WHERE t.slug = ?", String.class, slug);
    }

    private List<String> runs() {
        return platformJdbc.queryForList("SELECT CONCAT(tenant_slug, ':', action, ':', outcome) FROM provisioning_runs ORDER BY id",
                String.class);
    }

    private String runMessage(String slug) {
        return platformJdbc.queryForObject("SELECT TOP (1) ISNULL(message, '') FROM provisioning_runs WHERE tenant_slug = ? ORDER BY id DESC",
                String.class, slug);
    }

    private static JdbcTemplate jdbc(ThrowawayDatabase db) {
        return new JdbcTemplate(new DriverManagerDataSource(db.url(), db.username(), db.password()));
    }

    /** The test URL's options without databaseName (the platform stores the name on its own). */
    private static String optionsOf(String tail) {
        if (tail == null) {
            return null;
        }
        var kept = new ArrayList<String>();
        for (var part : tail.split(";")) {
            if (!part.isBlank() && !part.toLowerCase().startsWith("databasename=")) {
                kept.add(part);
            }
        }
        return kept.isEmpty() ? null : String.join(";", kept);
    }
}
