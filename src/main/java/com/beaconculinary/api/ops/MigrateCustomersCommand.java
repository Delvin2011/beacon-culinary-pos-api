package com.beaconculinary.api.ops;

import com.beaconculinary.api.tenancy.Customer;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.beaconculinary.api.tenancy.CustomerStatus;
import com.beaconculinary.api.tenancy.PlatformDatabase;
import com.beaconculinary.api.tenancy.secrets.MissingSecretException;
import com.beaconculinary.api.tenancy.secrets.SecretNames;
import com.beaconculinary.api.tenancy.secrets.SecretResolver;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.PrintStream;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T1-4: the operator's command that updates customer databases with the customer migrations
 * (docs/2026-11-26-customer-migrations.md). In multi-customer mode the app never migrates a customer database; this
 * command does, before a new app version starts. Run like the other operator commands:
 *
 * <pre>
 * java -cp app.jar -Dloader.main=com.beaconculinary.api.ops.MigrateCustomersCommand \
 *      org.springframework.boot.loader.launch.PropertiesLauncher [--customer SLUG] [--dry-run]
 * </pre>
 *
 * <ul>
 *   <li>Which customers: every ACTIVE and MIGRATION_FAILED customer; with {@code --customer}, that one only, which may
 *       also be SUSPENDED or PROVISIONING (its status is then left as it is). Never ARCHIVED.</li>
 *   <li>One at a time. Each: a provisioning_runs row (MIGRATE); the collation checked; Flyway with T0-7's settings
 *       ({@code db/migration}, plus {@code db/baseline} for a database built from it; no baseline-on-migrate, clean
 *       disabled), logged in as the customer's MIGRATION login (secret names from the platform database, values from
 *       {@link SecretResolver}). Success fills last_migrated_version and a MIGRATION_FAILED customer becomes ACTIVE; a
 *       failure makes an ACTIVE customer MIGRATION_FAILED (the app answers 503 CUSTOMER_UNAVAILABLE) and the command
 *       goes on with the next. A customer with no migration login registered, or a missing secret, is REFUSED
 *       (nothing changed).</li>
 *   <li>{@code --dry-run}: connects, checks the collation and reports what would be applied; writes nothing.</li>
 *   <li>Run twice, the second run has nothing to do (UNCHANGED). Two copies at the same time take turns on Flyway's lock
 *       (sp_getapplock per database, taken for each migration, waits up to an hour): each migration is applied by
 *       exactly one of them, and the other waits and skips it; both end SUCCEEDED or UNCHANGED.</li>
 * </ul>
 *
 * <p>The platform database: APP_TENANCY_PLATFORM_URL, _USERNAME and _PASSWORD; its own migrations are applied first.
 * Secrets locally: the environment (SECRET_&lt;NAME&gt;, {@link SecretNames}); Key Vault in T1-0b. Flyway's own logging is
 * off; nothing printed or stored holds a password, a login or a URL with credentials. Exit codes: 0 every customer
 * updated or unchanged, 2 usage, 3 refused (no such customer, or archived), 4 one or more customers failed or refused.
 */
public final class MigrateCustomersCommand {
    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int REFUSED = 3;
    public static final int FAILED = 4;

    static final String ACTION = "MIGRATE";
    private static final String FULL_HISTORY = "classpath:db/migration";
    private static final Set<CustomerStatus> BY_DEFAULT = Set.of(CustomerStatus.ACTIVE, CustomerStatus.MIGRATION_FAILED);
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,38}[a-z0-9]");
    private static final String USAGE_TEXT = """
            Usage: MigrateCustomersCommand [--customer SLUG] [--dry-run]
            Platform database from APP_TENANCY_PLATFORM_URL, APP_TENANCY_PLATFORM_USERNAME, APP_TENANCY_PLATFORM_PASSWORD;
            each customer's migration login from its secrets (SECRET_<NAME> in the environment).""";

    private final String platformUrl;
    private final String platformUsername;
    private final String platformPassword;
    private final SecretResolver secrets;
    private final PrintStream out;
    private final PrintStream err;
    private final Clock clock;
    private final String operator;
    private final List<String> extraLocations;

    public MigrateCustomersCommand(String platformUrl, String platformUsername, String platformPassword, SecretResolver secrets,
                                   PrintStream out, PrintStream err, Clock clock, String operator) {
        this(platformUrl, platformUsername, platformPassword, secrets, out, err, clock, operator, List.of());
    }

    /** Tests only: extra migration locations (a test-only migration); never reachable from the command line. */
    MigrateCustomersCommand(String platformUrl, String platformUsername, String platformPassword, SecretResolver secrets,
                            PrintStream out, PrintStream err, Clock clock, String operator, List<String> extraLocations) {
        this.platformUrl = platformUrl;
        this.platformUsername = platformUsername;
        this.platformPassword = platformPassword;
        this.secrets = secrets;
        this.out = out;
        this.err = err;
        this.clock = clock;
        this.operator = operator == null ? null : operator.substring(0, Math.min(100, operator.length()));
        this.extraLocations = List.copyOf(extraLocations);
    }

    public static void main(String[] args) {
        var env = System.getenv();
        if (blank(env.get("APP_TENANCY_PLATFORM_URL")) || blank(env.get("APP_TENANCY_PLATFORM_USERNAME"))
                || blank(env.get("APP_TENANCY_PLATFORM_PASSWORD"))) {
            System.err.println("Set APP_TENANCY_PLATFORM_URL, APP_TENANCY_PLATFORM_USERNAME and APP_TENANCY_PLATFORM_PASSWORD.");
            System.exit(USAGE);
        }
        SecretResolver fromEnvironment = name -> {
            var variable = SecretNames.environmentVariable(name);
            var value = System.getenv(variable);
            if (value == null || value.isEmpty()) {
                throw new MissingSecretException(name, "environment variable " + variable);
            }
            return value;
        };
        System.exit(new MigrateCustomersCommand(env.get("APP_TENANCY_PLATFORM_URL"), env.get("APP_TENANCY_PLATFORM_USERNAME"),
                env.get("APP_TENANCY_PLATFORM_PASSWORD"), fromEnvironment, System.out, System.err, Clock.systemUTC(),
                System.getProperty("user.name")).run(args));
    }

    /** Runs the command; returns the exit code. */
    public int run(String[] args) {
        String only = null;
        var dryRun = false;
        for (int i = 0; i < args.length; i++) {
            if ("--dry-run".equals(args[i])) {
                dryRun = true;
            } else if ("--customer".equals(args[i]) && i + 1 < args.length && only == null) {
                only = args[++i].trim();
                if (!SLUG.matcher(only).matches()) {
                    return usage("--customer must be a customer's slug (3-40 lower-case letters, digits and hyphens).");
                }
            } else {
                return usage("Unknown option: " + args[i]);
            }
        }
        if (Pattern.compile("(?i)[;?&](user|username|password|pwd|uid)\\s*=").matcher(platformUrl).find()) {
            return usage("APP_TENANCY_PLATFORM_URL must not contain a user or password: use APP_TENANCY_PLATFORM_USERNAME and _PASSWORD.");
        }
        List<Customer> customers;
        JdbcTemplate platform;
        try {
            Flyway.configure().dataSource(platformUrl, platformUsername, platformPassword).locations(PlatformDatabase.LOCATION)
                    .cleanDisabled(true).baselineOnMigrate(false).loggers(NewDatabaseCommand.QuietFlywayLogs.class.getName())
                    .load().migrate();
            platform = new JdbcTemplate(new DriverManagerDataSource(platformUrl, platformUsername, platformPassword));
            var directory = new CustomerDirectory(platform, clock, Duration.ZERO);
            if (only != null) {
                var found = directory.findBySlug(only);
                if (found.isEmpty()) {
                    err.println("Refused: there is no customer " + only + ".");
                    return REFUSED;
                }
                if (found.get().status() == CustomerStatus.ARCHIVED) {
                    err.println("Refused: " + only + " is ARCHIVED; its database is not updated.");
                    return REFUSED;
                }
                customers = List.of(found.get());
            } else {
                customers = directory.listAll().stream().filter(c -> BY_DEFAULT.contains(c.status())).toList();
            }
        } catch (RuntimeException e) {
            err.println("Failed: the platform database: " + oneLine(e, null, null));
            return FAILED;
        }
        if (customers.isEmpty()) {
            out.println("No customers to update.");
            return OK;
        }
        out.println(dryRun ? "Dry run: nothing is changed." : "Updating " + customers.size() + " customer database(s), one at a time.");
        var results = new ArrayList<Result>();
        for (var customer : customers) {
            results.add(dryRun ? preview(customer) : migrate(platform, customer));
        }
        print(results);
        return results.stream().anyMatch(r -> r.outcome().equals("FAILED") || r.outcome().equals("REFUSED")) ? FAILED : OK;
    }

    private record Result(String slug, String from, String to, String outcome, Duration took, String message) {
    }

    private record Login(String username, String password) {
    }

    /** One customer: a run row, the collation, Flyway, then the customer's status and last version. */
    private Result migrate(JdbcTemplate platform, Customer customer) {
        var started = clock.instant();
        var runId = platform.queryForObject("INSERT INTO provisioning_runs (tenant_id, tenant_slug, action, operator, started_at, outcome) "
                + "OUTPUT INSERTED.id VALUES (?, ?, ?, ?, ?, 'RUNNING')", Long.class, customer.id(), customer.slug(), ACTION, operator,
                Timestamp.from(started));
        String from = "-";
        Login login = null;
        Result result;
        try {
            var refusal = refusal(customer);
            if (refusal != null) {
                result = new Result(customer.slug(), "-", "-", "REFUSED", Duration.between(started, clock.instant()), refusal);
            } else {
                login = login(customer);
                var database = customer.database();
                checkCollation(database, login);
                var flyway = flyway(database, login);
                from = version(flyway.info().current());
                var applied = flyway.migrate();
                // The database's own version now: with a second copy taking turns, it may have applied later ones.
                var to = version(flyway.info().current());
                var outcome = applied.migrationsExecuted > 0 ? "SUCCEEDED" : "UNCHANGED";
                var now = Timestamp.from(clock.instant());
                recordVersion(platform, customer.id(), to, now);
                var back = platform.update("UPDATE tenants SET status = 'ACTIVE', updated_at = ? WHERE id = ? AND status = 'MIGRATION_FAILED'",
                        now, customer.id()) > 0;
                result = new Result(customer.slug(), from, to, outcome, Duration.between(started, clock.instant()),
                        applied.migrationsExecuted + " migration(s) applied" + (back ? "; MIGRATION_FAILED -> ACTIVE" : ""));
            }
        } catch (MissingSecretException e) {
            result = new Result(customer.slug(), from, "-", "REFUSED", Duration.between(started, clock.instant()), e.getMessage());
        } catch (Exception e) {
            var now = Timestamp.from(clock.instant());
            var failed = platform.update("UPDATE tenants SET status = 'MIGRATION_FAILED', updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
                    now, customer.id()) > 0;
            result = new Result(customer.slug(), from, "-", "FAILED", Duration.between(started, clock.instant()),
                    oneLine(e, login, customer.database()) + (failed ? "; ACTIVE -> MIGRATION_FAILED" : ""));
        }
        platform.update("UPDATE provisioning_runs SET finished_at = ?, outcome = ?, message = ? WHERE id = ?",
                Timestamp.from(clock.instant()), result.outcome(), cut(result.message(), 1000), runId);
        return result;
    }

    /**
     * Records the version reached, never an older one than recorded: two copies take turns on Flyway's lock per
     * migration, so the one that finishes last may have seen fewer migrations. Under an update lock on the row.
     */
    private static void recordVersion(JdbcTemplate platform, long tenantId, String version, Timestamp now) {
        if (version.startsWith("none")) {
            return;
        }
        new TransactionTemplate(new DataSourceTransactionManager(platform.getDataSource())).executeWithoutResult(status -> {
            var recorded = platform.queryForObject("SELECT last_migrated_version FROM tenant_databases WITH (UPDLOCK, HOLDLOCK) "
                    + "WHERE tenant_id = ?", String.class, tenantId);
            if (recorded == null || MigrationVersion.fromVersion(version).compareTo(MigrationVersion.fromVersion(recorded)) >= 0) {
                platform.update("UPDATE tenant_databases SET last_migrated_version = ?, last_migrated_at = ?, updated_at = ? "
                        + "WHERE tenant_id = ?", version, now, now, tenantId);
            }
        });
    }

    /** --dry-run: what would be applied; nothing written anywhere. */
    private Result preview(Customer customer) {
        var started = clock.instant();
        Login login = null;
        try {
            var refusal = refusal(customer);
            if (refusal != null) {
                return new Result(customer.slug(), "-", "-", "REFUSED", Duration.between(started, clock.instant()), refusal);
            }
            login = login(customer);
            checkCollation(customer.database(), login);
            var info = flyway(customer.database(), login).info();
            var from = version(info.current());
            var pending = info.pending();
            var to = pending.length == 0 ? from : version(pending[pending.length - 1]);
            return new Result(customer.slug(), from, to, pending.length == 0 ? "UP TO DATE" : "WOULD APPLY",
                    Duration.between(started, clock.instant()), pending.length + " migration(s) pending");
        } catch (MissingSecretException e) {
            return new Result(customer.slug(), "-", "-", "REFUSED", Duration.between(started, clock.instant()), e.getMessage());
        } catch (Exception e) {
            return new Result(customer.slug(), "-", "-", "FAILED", Duration.between(started, clock.instant()),
                    oneLine(e, login, customer.database()));
        }
    }

    private static String refusal(Customer customer) {
        var database = customer.database();
        if (database == null || database.databaseName() == null) {
            return "no database registered";
        }
        if (database.migrationLoginSecretName() == null || database.migrationPasswordSecretName() == null) {
            return "no migration login registered (TenantRegisterCommand register --migration-login-secret ... --migration-password-secret ...)";
        }
        return null;
    }

    /** The migration login, resolved now and used only for this customer's connections. */
    private Login login(Customer customer) {
        return new Login(secrets.resolve(customer.database().migrationLoginSecretName()),
                secrets.resolve(customer.database().migrationPasswordSecretName()));
    }

    private static void checkCollation(Customer.DatabaseLocation database, Login login) throws SQLException {
        try (var connection = DriverManager.getConnection(jdbcUrl(database), login.username(), login.password())) {
            var actual = NewDatabaseCommand.collation(connection);
            if (actual == null || !actual.equalsIgnoreCase(database.expectedCollation())) {
                throw new IllegalStateException("the database's collation is " + actual + ", expected " + database.expectedCollation()
                        + "; not migrated");
            }
        }
    }

    /** T0-7's settings: db/migration, plus db/baseline for a database built from it. */
    private Flyway flyway(Customer.DatabaseLocation database, Login login) {
        var locations = Stream.concat(
                (database.builtFromBaseline() ? Stream.of(NewDatabaseCommand.LOCATIONS) : Stream.of(FULL_HISTORY)),
                extraLocations.stream()).toArray(String[]::new);
        return Flyway.configure().dataSource(jdbcUrl(database), login.username(), login.password()).locations(locations)
                .cleanDisabled(true).baselineOnMigrate(false).loggers(NewDatabaseCommand.QuietFlywayLogs.class.getName()).load();
    }

    /** As the app's customer pools build it: no credentials in the URL. */
    private static String jdbcUrl(Customer.DatabaseLocation database) {
        var url = new StringBuilder("jdbc:sqlserver://").append(database.serverHost()).append(':').append(database.serverPort())
                .append(";databaseName={").append(database.databaseName().replace("}", "}}")).append('}');
        if (database.connectionOptions() != null && !database.connectionOptions().isBlank()) {
            url.append(';').append(database.connectionOptions().trim());
        }
        return url.toString();
    }

    private static String version(MigrationInfo info) {
        return info == null || info.getVersion() == null ? "none" : info.getVersion().getVersion();
    }

    private void print(List<Result> results) {
        out.println(String.format(Locale.ROOT, "%-24s %-8s %-8s %-12s %8s", "Customer", "From", "To", "Result", "Time"));
        for (var r : results) {
            out.println(String.format(Locale.ROOT, "%-24s %-8s %-8s %-12s %6.1f s", r.slug(), r.from(), r.to(), r.outcome(),
                    r.took().toMillis() / 1000.0));
        }
        for (var r : results) {
            if (r.outcome().equals("FAILED") || r.outcome().equals("REFUSED")) {
                out.println(r.slug() + ": " + cut(r.message(), 400));
            }
        }
        var failed = results.stream().filter(r -> r.outcome().equals("FAILED") || r.outcome().equals("REFUSED")).count();
        out.println(failed == 0 ? "Done." : failed + " customer(s) not updated; fix the cause and run the command again.");
    }

    /**
     * An error as one short line with nothing secret: the login and password are blanked if a driver message repeats
     * them, and the database URL is never part of it.
     */
    private static String oneLine(Exception e, Login login, Customer.DatabaseLocation database) {
        var text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        text = text.replaceAll("\\s+", " ").trim();
        if (login != null) {
            if (login.password() != null && !login.password().isEmpty()) {
                text = text.replace(login.password(), "[password]");
            }
            if (login.username() != null && !login.username().isEmpty()) {
                text = text.replaceAll("(?<![A-Za-z0-9_])" + Pattern.quote(login.username()) + "(?![A-Za-z0-9_])", "[login]");
            }
        }
        if (database != null) {
            text = text.replace(jdbcUrl(database), "[database]");
        }
        return cut(text, 1000);
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private int usage(String problem) {
        err.println(problem);
        err.println(USAGE_TEXT);
        return USAGE;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
