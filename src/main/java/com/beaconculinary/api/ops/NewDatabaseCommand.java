package com.beaconculinary.api.ops;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * T0-7: the operator's new-database command (docs/2026-11-22-new-customer-database.md).
 *
 * <p>Brings an EMPTY customer database to the current schema: the B{N} baseline (no demo data, only the
 * required reference rows), every migration after it, and, with {@code --starter}, the optional starter
 * template. Then the operator runs {@link FirstAdminCommand}. Plain Java with no Spring and no logger (Flyway's
 * own logging is switched off), so nothing about the database reaches a log; the summary goes to this
 * terminal. It is one of the two files allowed to print to the console (NoConsolePrintingTest).
 *
 * <pre>
 * java -cp app.jar -Dloader.main=com.beaconculinary.api.ops.NewDatabaseCommand \
 *      org.springframework.boot.loader.launch.PropertiesLauncher [--starter]
 * </pre>
 *
 * <p>Database: SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD from the
 * environment, never printed. Refuses a database that already has tables or migration history: existing
 * databases are migrated by the app as usual.
 *
 * <p>Exit codes: 0 done, 2 usage, 3 refused (not empty), 4 failed.
 */
public final class NewDatabaseCommand {
    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int REFUSED = 3;
    public static final int FAILED = 4;

    static final String[] LOCATIONS = {"classpath:db/migration", "classpath:db/baseline"};
    static final String STARTER = "db/starter/starter.sql";

    private static final String USAGE_TEXT = """
            Usage: [--starter]
            Database from SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD.""";

    private final String url;
    private final String username;
    private final String password;
    private final PrintStream out;
    private final PrintStream err;
    private final Clock clock;

    public NewDatabaseCommand(String url, String username, String password, PrintStream out, PrintStream err, Clock clock) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.out = out;
        this.err = err;
        this.clock = clock;
    }

    public static void main(String[] args) {
        Map<String, String> env = System.getenv();
        var url = env.get("SPRING_DATASOURCE_URL");
        if (url == null || url.isBlank()) {
            System.err.println("SPRING_DATASOURCE_URL is not set (with SPRING_DATASOURCE_USERNAME and _PASSWORD).");
            System.exit(USAGE);
        }
        var command = new NewDatabaseCommand(url, env.get("SPRING_DATASOURCE_USERNAME"), env.get("SPRING_DATASOURCE_PASSWORD"),
                System.out, System.err, Clock.systemUTC());
        System.exit(command.run(args));
    }

    /** Runs the command; returns the exit code. */
    public int run(String[] args) {
        var starter = false;
        for (var arg : args) {
            if ("--starter".equals(arg)) {
                starter = true;
            } else {
                err.println("Unknown option: " + arg);
                err.println(USAGE_TEXT);
                return USAGE;
            }
        }
        var started = clock.instant();
        try {
            try (var connection = connect()) {
                var found = existingObjects(connection);
                if (found > 0) {
                    err.println("Refused: the database isn't empty (" + found + " tables). This command only sets up a new, "
                            + "empty database; existing databases are migrated by the app.");
                    return REFUSED;
                }
            }
            var result = Flyway.configure().dataSource(url, username, password).locations(LOCATIONS).cleanDisabled(true)
                    .baselineOnMigrate(false).loggers(QuietFlywayLogs.class.getName()).load().migrate();
            out.println("Schema at version " + result.targetSchemaVersion + ": " + result.migrationsExecuted
                    + " migration(s) applied (the baseline and any after it).");
            if (starter) {
                try (var connection = connect()) {
                    applyStarter(connection);
                }
                out.println("Starter template applied (meal periods, catalogue subcategories, component and count-sheet categories).");
            } else {
                out.println("No starter template (run again with --starter on a new database to include it).");
            }
            out.println("Done in " + Duration.between(started, clock.instant()).toMillis() + " ms. "
                    + "Next: create the first admin with FirstAdminCommand.");
            return OK;
        } catch (SQLException e) {
            err.println("Failed: database error (SQL state " + e.getSQLState() + "): " + e.getMessage());
            return FAILED;
        } catch (FlywayException e) {
            err.println("Failed: " + e.getMessage());
            return FAILED;
        } catch (IOException | RuntimeException e) {
            err.println("Failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return FAILED;
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    /** User tables in dbo, the Flyway history included. */
    static int existingObjects(Connection connection) throws SQLException {
        try (var st = connection.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM sys.tables WHERE SCHEMA_NAME(schema_id) = 'dbo' AND is_ms_shipped = 0")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Runs the starter script as one batch (it manages its own transaction). */
    static void applyStarter(Connection connection) throws IOException, SQLException {
        try (var in = NewDatabaseCommand.class.getClassLoader().getResourceAsStream(STARTER)) {
            if (in == null) {
                throw new IOException(STARTER + " not found in the application jar");
            }
            var sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (var st = connection.createStatement()) {
                st.execute(sql);
            }
        }
    }

    /**
     * Flyway's own messages include the full database URL; none of them is needed here. Passed to Flyway by
     * class name ({@code loggers}), which it instantiates in place of its auto-detected logging.
     */
    public static final class QuietFlywayLogs implements LogCreator {
        @Override
        public Log createLogger(Class<?> type) {
            return QUIET;
        }
    }

    private static final Log QUIET = new Log() {
            @Override
            public boolean isDebugEnabled() {
                return false;
            }

            @Override
            public void debug(String message) {
            }

            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
            }

            @Override
            public void error(String message) {
            }

            @Override
            public void error(String message, Exception e) {
            }

            @Override
            public void notice(String message) {
            }
        };
}
