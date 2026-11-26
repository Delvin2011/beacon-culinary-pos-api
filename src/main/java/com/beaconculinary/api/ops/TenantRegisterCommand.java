package com.beaconculinary.api.ops;

import com.beaconculinary.api.tenancy.CustomerStatus;
import com.beaconculinary.api.tenancy.HostNames;
import com.beaconculinary.api.tenancy.PlatformDatabase;
import com.beaconculinary.api.tenancy.secrets.SecretNames;
import org.flywaydb.core.Flyway;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * T1-1: the operator's command for the platform database (docs/2026-11-24-platform-database.md): registers customers,
 * their addresses and where their databases are, and changes them. Run like NewDatabaseCommand and FirstAdminCommand:
 *
 * <pre>
 * java -cp app.jar -Dloader.main=com.beaconculinary.api.ops.TenantRegisterCommand \
 *      org.springframework.boot.loader.launch.PropertiesLauncher &lt;action&gt; [options]
 *
 *   migrate
 *   register --slug acme --name "Acme Foods" --host acme.pos.optimumscs.com [--host ...]
 *            --db-server sql-pos.database.windows.net [--db-port 1433] --db-name pos [--db-options "encrypt=true"]
 *            --login-secret acme-db-login --password-secret acme-db-password
 *            --built-from-baseline true|false [--collation SQL_Latin1_General_CP1_CI_AS] [--status ACTIVE]
 *            [--migration-login-secret acme-db-migration-login --migration-password-secret acme-db-migration-password]
 *   suspend --slug S          activate --slug S
 *   add-host --slug S --host H [--primary]          remove-host --slug S --host H
 *   show [--slug S]
 * </pre>
 *
 * <p>The platform database: APP_TENANCY_PLATFORM_URL (no credentials in it), APP_TENANCY_PLATFORM_USERNAME and
 * APP_TENANCY_PLATFORM_PASSWORD, from the environment only, never printed. Every action first applies the platform
 * migrations. register is declarative and idempotent: the hosts given are the customer's hosts (the first is the
 * primary), the same values again change nothing (UNCHANGED). A host or a database that belongs to another customer is
 * refused. Each change is one transaction under an application lock and records a provisioning_runs row; nothing
 * secret is printed or stored (only secret NAMES). T1-4: the migration login's secret names (used only by
 * MigrateCustomersCommand) are given together or not at all; left out, the stored ones are kept.
 *
 * <p>Exit codes: 0 done (or unchanged), 2 usage, 3 refused, 4 failed.
 */
public final class TenantRegisterCommand {
    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int REFUSED = 3;
    public static final int FAILED = 4;

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,38}[a-z0-9]");
    private static final Pattern SERVER = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final Pattern COLLATION = Pattern.compile("[A-Za-z0-9_]{1,128}");
    private static final Pattern CREDENTIALS = Pattern.compile("(?i)password|pwd|user|uid\\s*=|secret");
    private static final String LOCK = "pos-platform-register";
    private static final String USAGE_TEXT = """
            Usage: TenantRegisterCommand <action> [options]
              migrate
              register --slug S --name "Name" --host H [--host H ...] --db-server S [--db-port 1433] --db-name D
                       [--db-options "encrypt=true"] --login-secret NAME --password-secret NAME
                       --built-from-baseline true|false [--collation C] [--status ACTIVE]
                       [--migration-login-secret NAME --migration-password-secret NAME]
              suspend --slug S | activate --slug S
              add-host --slug S --host H [--primary] | remove-host --slug S --host H
              show [--slug S]
            Platform database from APP_TENANCY_PLATFORM_URL, APP_TENANCY_PLATFORM_USERNAME, APP_TENANCY_PLATFORM_PASSWORD.""";

    private final String url;
    private final String username;
    private final String password;
    private final PrintStream out;
    private final PrintStream err;
    private final Clock clock;
    private final String operator;

    public TenantRegisterCommand(String url, String username, String password, PrintStream out, PrintStream err,
                                 Clock clock, String operator) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.out = out;
        this.err = err;
        this.clock = clock;
        this.operator = operator == null ? null : operator.substring(0, Math.min(100, operator.length()));
    }

    public static void main(String[] args) {
        var env = System.getenv();
        var url = env.get("APP_TENANCY_PLATFORM_URL");
        if (url == null || url.isBlank() || blank(env.get("APP_TENANCY_PLATFORM_USERNAME"))
                || blank(env.get("APP_TENANCY_PLATFORM_PASSWORD"))) {
            System.err.println("Set APP_TENANCY_PLATFORM_URL, APP_TENANCY_PLATFORM_USERNAME and APP_TENANCY_PLATFORM_PASSWORD.");
            System.exit(USAGE);
        }
        var code = new TenantRegisterCommand(url, env.get("APP_TENANCY_PLATFORM_USERNAME"), env.get("APP_TENANCY_PLATFORM_PASSWORD"),
                System.out, System.err, Clock.systemUTC(), System.getProperty("user.name")).run(args);
        System.exit(code);
    }

    /** Runs the command; returns the exit code. */
    public int run(String[] args) {
        if (args.length == 0) {
            return usage("An action is required.");
        }
        var action = args[0];
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (Pattern.compile("(?i)[;?&](user|username|password|pwd|uid)\\s*=").matcher(url).find()) {
            return usage("APP_TENANCY_PLATFORM_URL must not contain a user or password: use APP_TENANCY_PLATFORM_USERNAME and _PASSWORD.");
        }
        try {
            switch (action) {
                case "migrate" -> {
                    options.allow();
                    return migrate();
                }
                case "register" -> {
                    var request = Registration.from(options);
                    migrateQuietly();
                    return change(request.slug(), "REGISTER", connection -> register(connection, request));
                }
                case "suspend", "activate" -> {
                    options.allow("slug");
                    var slug = options.slug();
                    migrateQuietly();
                    var status = action.equals("suspend") ? CustomerStatus.SUSPENDED : CustomerStatus.ACTIVE;
                    return change(slug, action.toUpperCase(Locale.ROOT), connection -> setStatus(connection, slug, status));
                }
                case "add-host" -> {
                    options.allow("slug", "host", "primary");
                    var slug = options.slug();
                    var host = options.host();
                    var primary = options.flag("primary");
                    migrateQuietly();
                    return change(slug, "ADD_HOST", connection -> addHost(connection, slug, host, primary));
                }
                case "remove-host" -> {
                    options.allow("slug", "host");
                    var slug = options.slug();
                    var host = options.host();
                    migrateQuietly();
                    return change(slug, "REMOVE_HOST", connection -> removeHost(connection, slug, host));
                }
                case "show" -> {
                    options.allow("slug");
                    var slug = options.has("slug") ? options.slug() : null;
                    migrateQuietly();
                    return show(slug);
                }
                default -> {
                    return usage("Unknown action: " + action);
                }
            }
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        } catch (SQLException e) {
            // The driver's message can name the server but never the password.
            err.println("Failed: database error (SQL state " + e.getSQLState() + "): " + e.getMessage());
            return FAILED;
        } catch (RuntimeException e) {
            err.println("Failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return FAILED;
        }
    }

    // --- actions ---------------------------------------------------------------------------------------------------

    private int migrate() {
        var flyway = flyway();
        var result = flyway.migrate();
        // targetSchemaVersion is empty when nothing was applied; the history says where the database is.
        var current = flyway.info().current();
        out.println("Platform database at version " + (current == null ? "none" : current.getVersion().getVersion()) + ": "
                + result.migrationsExecuted + " migration(s) applied now.");
        return OK;
    }

    private void migrateQuietly() {
        flyway().migrate();
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(url, username, password).locations(PlatformDatabase.LOCATION)
                .cleanDisabled(true).baselineOnMigrate(false)
                .loggers(NewDatabaseCommand.QuietFlywayLogs.class.getName()).load();
    }

    private Outcome register(Connection c, Registration r) throws SQLException {
        var existing = tenantId(c, r.slug());
        // Clashes: another customer's host or database.
        for (var host : r.hosts()) {
            var owner = hostOwner(c, host);
            if (owner != null && !owner.equals(existing)) {
                return Outcome.refused("host " + host + " belongs to customer " + slugOf(c, owner));
            }
        }
        var dbOwner = queryLong(c, "SELECT tenant_id FROM tenant_databases WHERE server_host = ? AND server_port = ? AND database_name = ?",
                r.dbServer(), r.dbPort(), r.dbName());
        if (dbOwner != null && !dbOwner.equals(existing)) {
            return Outcome.refused("database " + r.dbServer() + ":" + r.dbPort() + "/" + r.dbName() + " belongs to customer " + slugOf(c, dbOwner));
        }
        var now = Timestamp.from(clock.instant());
        if (existing == null) {
            var id = queryLong(c, "INSERT INTO tenants (slug, display_name, status, created_at, updated_at) OUTPUT INSERTED.id "
                    + "VALUES (?, ?, ?, ?, ?)", r.slug(), r.name(), r.status().name(), now, now);
            for (var i = 0; i < r.hosts().size(); i++) {
                update(c, "INSERT INTO tenant_hosts (tenant_id, host, is_primary, created_at) VALUES (?, ?, ?, ?)", id, r.hosts().get(i), i == 0, now);
            }
            insertDatabase(c, id, r, now);
            return Outcome.succeeded(id, "registered " + r.slug() + " (" + r.status() + ", " + r.hosts().size() + " host(s))");
        }
        var changes = new ArrayList<String>();
        var current = queryRow(c, "SELECT display_name, status FROM tenants WHERE id = ?", existing);
        if (!r.name().equals(current.get(0)) || !r.status().name().equals(current.get(1))) {
            update(c, "UPDATE tenants SET display_name = ?, status = ?, updated_at = ? WHERE id = ?", r.name(), r.status().name(), now, existing);
            changes.add("name/status");
        }
        var hosts = queryStrings(c, "SELECT host FROM tenant_hosts WHERE tenant_id = ? ORDER BY is_primary DESC, host", existing);
        // Ordered primary first, so the first host is the primary when there is one.
        var primary = queryLong(c, "SELECT COUNT(*) FROM tenant_hosts WHERE tenant_id = ? AND is_primary = 1", existing) > 0
                ? hosts.get(0) : null;
        if (!sameSet(hosts, r.hosts()) || !Objects.equals(primary, r.hosts().get(0))) {
            update(c, "DELETE FROM tenant_hosts WHERE tenant_id = ?", existing);
            for (var i = 0; i < r.hosts().size(); i++) {
                update(c, "INSERT INTO tenant_hosts (tenant_id, host, is_primary, created_at) VALUES (?, ?, ?, ?)", existing, r.hosts().get(i), i == 0, now);
            }
            changes.add("hosts");
        }
        var database = queryRow(c, "SELECT server_host, CAST(server_port AS varchar(10)), database_name, connection_options, login_secret_name, "
                + "password_secret_name, CAST(CAST(built_from_baseline AS int) AS varchar(1)), expected_collation, "
                + "migration_login_secret_name, migration_password_secret_name FROM tenant_databases WHERE tenant_id = ?", existing);
        if (database == null) {
            insertDatabase(c, existing, r, now);
            changes.add("database");
        } else {
            // T1-4: the migration secrets left out keep the stored ones.
            var migrationLogin = r.migrationLoginSecret() == null ? database.get(8) : r.migrationLoginSecret();
            var migrationPassword = r.migrationLoginSecret() == null ? database.get(9) : r.migrationPasswordSecret();
            var wanted = java.util.Arrays.asList(r.dbServer(), String.valueOf(r.dbPort()), r.dbName(), r.dbOptions(), r.loginSecret(),
                    r.passwordSecret(), r.builtFromBaseline() ? "1" : "0", r.collation(), migrationLogin, migrationPassword);
            if (!database.equals(wanted)) {
                update(c, "UPDATE tenant_databases SET server_host = ?, server_port = ?, database_name = ?, connection_options = ?, "
                        + "login_secret_name = ?, password_secret_name = ?, built_from_baseline = ?, expected_collation = ?, "
                        + "migration_login_secret_name = ?, migration_password_secret_name = ?, updated_at = ? "
                        + "WHERE tenant_id = ?", r.dbServer(), r.dbPort(), r.dbName(), r.dbOptions(), r.loginSecret(), r.passwordSecret(),
                        r.builtFromBaseline(), r.collation(), migrationLogin, migrationPassword, now, existing);
                changes.add("database");
            }
        }
        if (changes.isEmpty()) {
            return Outcome.unchanged(existing, r.slug() + " is already registered with these values");
        }
        if (!changes.contains("name/status")) {
            update(c, "UPDATE tenants SET updated_at = ? WHERE id = ?", now, existing);
        }
        return Outcome.succeeded(existing, "updated " + r.slug() + ": " + String.join(", ", changes));
    }

    private Outcome setStatus(Connection c, String slug, CustomerStatus status) throws SQLException {
        var id = tenantId(c, slug);
        if (id == null) {
            return Outcome.refused("no customer " + slug);
        }
        var current = queryRow(c, "SELECT status FROM tenants WHERE id = ?", id).get(0);
        if (status.name().equals(current)) {
            return Outcome.unchanged(id, slug + " is already " + status);
        }
        if (status == CustomerStatus.ACTIVE) {
            if (queryLong(c, "SELECT COUNT(*) FROM tenant_databases WHERE tenant_id = ?", id) == 0
                    || queryLong(c, "SELECT COUNT(*) FROM tenant_hosts WHERE tenant_id = ? AND is_primary = 1", id) == 0) {
                return Outcome.refused(slug + " can't be ACTIVE without a database and a primary host");
            }
        }
        update(c, "UPDATE tenants SET status = ?, updated_at = ? WHERE id = ?", status.name(), Timestamp.from(clock.instant()), id);
        return Outcome.succeeded(id, slug + ": " + current + " -> " + status);
    }

    private Outcome addHost(Connection c, String slug, String host, boolean makePrimary) throws SQLException {
        var id = tenantId(c, slug);
        if (id == null) {
            return Outcome.refused("no customer " + slug);
        }
        var owner = hostOwner(c, host);
        if (owner != null && !owner.equals(id)) {
            return Outcome.refused("host " + host + " belongs to customer " + slugOf(c, owner));
        }
        var hasPrimary = queryLong(c, "SELECT COUNT(*) FROM tenant_hosts WHERE tenant_id = ? AND is_primary = 1", id) > 0;
        var now = Timestamp.from(clock.instant());
        if (owner != null) {
            var isPrimary = queryLong(c, "SELECT COUNT(*) FROM tenant_hosts WHERE host = ? AND is_primary = 1", host) > 0;
            if (!makePrimary || isPrimary) {
                return Outcome.unchanged(id, host + " is already a host of " + slug);
            }
            update(c, "UPDATE tenant_hosts SET is_primary = 0 WHERE tenant_id = ?", id);
            update(c, "UPDATE tenant_hosts SET is_primary = 1 WHERE host = ?", host);
            update(c, "UPDATE tenants SET updated_at = ? WHERE id = ?", now, id);
            return Outcome.succeeded(id, host + " is now the primary host of " + slug);
        }
        var primary = makePrimary || !hasPrimary;
        if (primary) {
            update(c, "UPDATE tenant_hosts SET is_primary = 0 WHERE tenant_id = ?", id);
        }
        update(c, "INSERT INTO tenant_hosts (tenant_id, host, is_primary, created_at) VALUES (?, ?, ?, ?)", id, host, primary, now);
        update(c, "UPDATE tenants SET updated_at = ? WHERE id = ?", now, id);
        return Outcome.succeeded(id, "added " + host + " to " + slug + (primary ? " (primary)" : ""));
    }

    private Outcome removeHost(Connection c, String slug, String host) throws SQLException {
        var id = tenantId(c, slug);
        if (id == null) {
            return Outcome.refused("no customer " + slug);
        }
        var owner = hostOwner(c, host);
        if (owner == null || !owner.equals(id)) {
            return Outcome.refused(host + " is not a host of " + slug);
        }
        var count = queryLong(c, "SELECT COUNT(*) FROM tenant_hosts WHERE tenant_id = ?", id);
        var isPrimary = queryLong(c, "SELECT COUNT(*) FROM tenant_hosts WHERE host = ? AND is_primary = 1", host) > 0;
        if (isPrimary && count > 1) {
            return Outcome.refused(host + " is the primary host of " + slug + ": make another host primary first (add-host --primary)");
        }
        var status = queryRow(c, "SELECT status FROM tenants WHERE id = ?", id).get(0);
        if (count == 1 && CustomerStatus.ACTIVE.name().equals(status)) {
            return Outcome.refused(host + " is the only host of " + slug + ", which is ACTIVE: suspend it first");
        }
        update(c, "DELETE FROM tenant_hosts WHERE host = ?", host);
        update(c, "UPDATE tenants SET updated_at = ? WHERE id = ?", Timestamp.from(clock.instant()), id);
        return Outcome.succeeded(id, "removed " + host + " from " + slug);
    }

    private int show(String slug) throws SQLException {
        try (var c = connect()) {
            var tenants = slug == null
                    ? queryRows(c, "SELECT id, slug, display_name, status FROM tenants ORDER BY slug")
                    : queryRows(c, "SELECT id, slug, display_name, status FROM tenants WHERE slug = ?", slug);
            if (tenants.isEmpty()) {
                out.println(slug == null ? "No customers registered." : "No customer " + slug + ".");
                return slug == null ? OK : REFUSED;
            }
            for (var t : tenants) {
                var id = Long.parseLong(t.get(0));
                out.println(t.get(1) + "  " + t.get(3) + "  \"" + t.get(2) + "\"");
                for (var h : queryRows(c, "SELECT host, CAST(CAST(is_primary AS int) AS varchar(1)) FROM tenant_hosts WHERE tenant_id = ? "
                        + "ORDER BY is_primary DESC, host", id)) {
                    out.println("  host      " + h.get(0) + ("1".equals(h.get(1)) ? "  (primary)" : ""));
                }
                var d = queryRow(c, "SELECT server_host, CAST(server_port AS varchar(10)), database_name, ISNULL(connection_options, ''), "
                        + "login_secret_name, password_secret_name, CAST(CAST(built_from_baseline AS int) AS varchar(1)), expected_collation, "
                        + "ISNULL(last_migrated_version, '-'), ISNULL(migration_login_secret_name, ''), ISNULL(migration_password_secret_name, '') "
                        + "FROM tenant_databases WHERE tenant_id = ?", id);
                if (d == null) {
                    out.println("  database  (none registered)");
                } else {
                    out.println("  database  " + d.get(0) + ":" + d.get(1) + "/" + d.get(2) + (d.get(3).isEmpty() ? "" : "  options " + d.get(3)));
                    out.println("  secrets   login " + d.get(4) + ", password " + d.get(5) + "  (names only)");
                    out.println("  migration " + (d.get(9).isEmpty() ? "(no migration login registered)"
                            : "login " + d.get(9) + ", password " + d.get(10) + "  (names only)"));
                    out.println("  built     " + ("1".equals(d.get(6)) ? "from the baseline" : "with full history")
                            + ", collation " + d.get(7) + ", last migrated " + d.get(8));
                }
            }
            return OK;
        }
    }

    // --- one change: lock, transaction, run row --------------------------------------------------------------------

    @FunctionalInterface
    private interface Change {
        Outcome apply(Connection connection) throws SQLException;
    }

    private int change(String slug, String action, Change change) throws SQLException {
        var started = clock.instant();
        try (var c = connect()) {
            if (!lock(c)) {
                err.println("Refused: another platform change is running; try again.");
                return REFUSED;
            }
            try {
                Outcome outcome;
                c.setAutoCommit(false);
                try {
                    outcome = change.apply(c);
                    if (outcome.code() == OK) {
                        var runAction = action.equals("REGISTER") && outcome.message().startsWith("updated") ? "UPDATE" : action;
                        recordRun(c, outcome.tenantId(), slug, runAction, started, outcome.outcome(), outcome.message());
                        c.commit();
                    } else {
                        c.rollback();
                    }
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    c.setAutoCommit(true);
                    recordRun(c, tenantId(c, slug), slug, action, started, "FAILED",
                            e.getClass().getSimpleName() + (e instanceof SQLException s ? " (SQL state " + s.getSQLState() + ")" : ""));
                    throw e;
                }
                c.setAutoCommit(true);
                if (outcome.code() != OK) {
                    recordRun(c, outcome.tenantId(), slug, action, started, "REFUSED", outcome.message());
                    err.println("Refused: " + outcome.message() + ".");
                    return outcome.code();
                }
                out.println((outcome.outcome().equals("UNCHANGED") ? "Unchanged: " : "Done: ") + outcome.message() + ".");
                return OK;
            } finally {
                unlock(c);
            }
        }
    }

    private void recordRun(Connection c, Long tenantId, String slug, String action, Instant started, String outcome,
                           String message) throws SQLException {
        update(c, "INSERT INTO provisioning_runs (tenant_id, tenant_slug, action, operator, started_at, finished_at, outcome, message) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", tenantId, slug, action, operator, Timestamp.from(started),
                Timestamp.from(clock.instant()), outcome, message == null ? null : message.substring(0, Math.min(1000, message.length())));
    }

    private boolean lock(Connection c) throws SQLException {
        try (var call = c.prepareStatement("DECLARE @r INT; EXEC @r = sp_getapplock @Resource = '" + LOCK + "', @LockMode = 'Exclusive', "
                + "@LockOwner = 'Session', @LockTimeout = 10000; SELECT @r AS [result]"); var rs = call.executeQuery()) {
            return rs.next() && rs.getInt(1) >= 0;
        }
    }

    private void unlock(Connection c) {
        try (var call = c.prepareStatement("EXEC sp_releaseapplock @Resource = '" + LOCK + "', @LockOwner = 'Session'")) {
            call.execute();
        } catch (SQLException ignored) {
            // The lock goes with the session anyway.
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    // --- small JDBC helpers ----------------------------------------------------------------------------------------

    private void insertDatabase(Connection c, long tenant, Registration r, Timestamp now) throws SQLException {
        update(c, "INSERT INTO tenant_databases (tenant_id, server_host, server_port, database_name, connection_options, login_secret_name, "
                        + "password_secret_name, built_from_baseline, expected_collation, migration_login_secret_name, "
                        + "migration_password_secret_name, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tenant, r.dbServer(), r.dbPort(), r.dbName(), r.dbOptions(), r.loginSecret(), r.passwordSecret(),
                r.builtFromBaseline(), r.collation(), r.migrationLoginSecret(), r.migrationPasswordSecret(), now);
    }

    private static Long tenantId(Connection c, String slug) throws SQLException {
        return queryLong(c, "SELECT id FROM tenants WHERE slug = ?", slug);
    }

    private static Long hostOwner(Connection c, String host) throws SQLException {
        return queryLong(c, "SELECT tenant_id FROM tenant_hosts WHERE host = ?", host);
    }

    private static String slugOf(Connection c, long id) throws SQLException {
        return queryRow(c, "SELECT slug FROM tenants WHERE id = ?", id).get(0);
    }

    private static boolean sameSet(List<String> a, List<String> b) {
        return new LinkedHashSet<>(a).equals(new LinkedHashSet<>(b));
    }

    private static void update(Connection c, String sql, Object... values) throws SQLException {
        try (var st = c.prepareStatement(sql)) {
            bind(st, values);
            st.executeUpdate();
        }
    }

    private static Long queryLong(Connection c, String sql, Object... values) throws SQLException {
        try (var st = c.prepareStatement(sql)) {
            bind(st, values);
            try (var rs = st.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static List<String> queryStrings(Connection c, String sql, Object... values) throws SQLException {
        var result = new ArrayList<String>();
        for (var row : queryRows(c, sql, values)) {
            result.add(row.get(0));
        }
        return result;
    }

    private static List<String> queryRow(Connection c, String sql, Object... values) throws SQLException {
        var rows = queryRows(c, sql, values);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<List<String>> queryRows(Connection c, String sql, Object... values) throws SQLException {
        try (var st = c.prepareStatement(sql)) {
            bind(st, values);
            try (var rs = st.executeQuery()) {
                var rows = new ArrayList<List<String>>();
                var columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    var row = new ArrayList<String>(columns);
                    for (var i = 1; i <= columns; i++) {
                        row.add(rs.getString(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private static void bind(java.sql.PreparedStatement st, Object... values) throws SQLException {
        for (var i = 0; i < values.length; i++) {
            st.setObject(i + 1, values[i]);
        }
    }

    private int usage(String message) {
        err.println(message);
        err.println(USAGE_TEXT);
        return USAGE;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // --- options -----------------------------------------------------------------------------------------------------

    /** What an action did: OK (SUCCEEDED or UNCHANGED) or REFUSED, with a message that never holds a secret. */
    private record Outcome(int code, Long tenantId, String outcome, String message) {
        static Outcome succeeded(long tenantId, String message) {
            return new Outcome(OK, tenantId, "SUCCEEDED", message);
        }

        static Outcome unchanged(long tenantId, String message) {
            return new Outcome(OK, tenantId, "UNCHANGED", message);
        }

        static Outcome refused(String message) {
            return new Outcome(REFUSED, null, "REFUSED", message);
        }
    }

    /** --name value pairs (--host may repeat; --primary is a flag). */
    private static final class Options {
        private final Map<String, List<String>> values = new HashMap<>();

        static Options parse(String[] args) {
            var options = new Options();
            for (var i = 1; i < args.length; i++) {
                var arg = args[i];
                if (!arg.startsWith("--") || arg.length() < 3) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                var name = arg.substring(2);
                if (name.equals("primary")) {
                    options.values.computeIfAbsent(name, k -> new ArrayList<>()).add("true");
                    continue;
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(arg + " needs a value.");
                }
                options.values.computeIfAbsent(name, k -> new ArrayList<>()).add(args[++i]);
            }
            return options;
        }

        void allow(String... names) {
            var allowed = java.util.Set.of(names);
            for (var name : values.keySet()) {
                if (!allowed.contains(name)) {
                    throw new IllegalArgumentException("Unknown option for this action: --" + name);
                }
            }
        }

        boolean has(String name) {
            return values.containsKey(name);
        }

        boolean flag(String name) {
            return values.containsKey(name);
        }

        String one(String name) {
            var list = values.get(name);
            if (list == null || list.isEmpty() || list.get(0).isBlank()) {
                throw new IllegalArgumentException("--" + name + " is required.");
            }
            if (list.size() > 1) {
                throw new IllegalArgumentException("--" + name + " is given more than once.");
            }
            return list.get(0).trim();
        }

        String optional(String name, String fallback) {
            return has(name) ? one(name) : fallback;
        }

        List<String> all(String name) {
            return values.getOrDefault(name, List.of());
        }

        String slug() {
            var slug = one("slug");
            if (!SLUG.matcher(slug).matches()) {
                throw new IllegalArgumentException("--slug must be 3-40 lower-case letters, digits and hyphens, starting and ending with a letter or digit.");
            }
            return slug;
        }

        String host() {
            var raw = one("host");
            return HostNames.normalise(raw).orElseThrow(() -> new IllegalArgumentException("Not a host name: " + raw));
        }
    }

    /** register's values, checked before anything connects. */
    private record Registration(String slug, String name, List<String> hosts, String dbServer, int dbPort, String dbName,
                                String dbOptions, String loginSecret, String passwordSecret, boolean builtFromBaseline,
                                String collation, CustomerStatus status, String migrationLoginSecret,
                                String migrationPasswordSecret) {

        static Registration from(Options o) {
            o.allow("slug", "name", "host", "db-server", "db-port", "db-name", "db-options", "login-secret", "password-secret",
                    "built-from-baseline", "collation", "status", "migration-login-secret", "migration-password-secret");
            var slug = o.slug();
            var name = o.one("name");
            if (name.length() > 200) {
                throw new IllegalArgumentException("--name is at most 200 characters.");
            }
            var hosts = new ArrayList<String>();
            for (var raw : o.all("host")) {
                var host = HostNames.normalise(raw).orElseThrow(() -> new IllegalArgumentException("Not a host name: " + raw));
                if (!hosts.contains(host)) {
                    hosts.add(host);
                }
            }
            if (hosts.isEmpty()) {
                throw new IllegalArgumentException("At least one --host is required (the first is the primary).");
            }
            var server = o.one("db-server");
            if (!SERVER.matcher(server).matches()) {
                throw new IllegalArgumentException("--db-server must be a host name (no port, no credentials).");
            }
            int port;
            try {
                port = Integer.parseInt(o.optional("db-port", "1433"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--db-port must be a number.");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("--db-port must be 1 to 65535.");
            }
            var dbName = o.one("db-name");
            if (dbName.length() > 128) {
                throw new IllegalArgumentException("--db-name is at most 128 characters.");
            }
            var options = o.has("db-options") ? o.one("db-options") : null;
            if (options != null && (options.length() > 500 || CREDENTIALS.matcher(options).find())) {
                throw new IllegalArgumentException("--db-options must not hold credentials (user, password, secret) and is at most 500 characters.");
            }
            var loginSecret = secretName(o.one("login-secret"), "--login-secret");
            var passwordSecret = secretName(o.one("password-secret"), "--password-secret");
            if (o.has("migration-login-secret") != o.has("migration-password-secret")) {
                throw new IllegalArgumentException("--migration-login-secret and --migration-password-secret go together.");
            }
            var migrationLoginSecret = o.has("migration-login-secret")
                    ? secretName(o.one("migration-login-secret"), "--migration-login-secret") : null;
            var migrationPasswordSecret = o.has("migration-password-secret")
                    ? secretName(o.one("migration-password-secret"), "--migration-password-secret") : null;
            var baseline = o.one("built-from-baseline").toLowerCase(Locale.ROOT);
            if (!baseline.equals("true") && !baseline.equals("false")) {
                throw new IllegalArgumentException("--built-from-baseline must be true or false.");
            }
            var collation = o.optional("collation", NewDatabaseCommand.DEFAULT_COLLATION);
            if (!COLLATION.matcher(collation).matches()) {
                throw new IllegalArgumentException("--collation must be a collation name.");
            }
            CustomerStatus status;
            try {
                status = CustomerStatus.valueOf(o.optional("status", "ACTIVE").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("--status must be one of PROVISIONING, ACTIVE, SUSPENDED, MIGRATION_FAILED, ARCHIVED.");
            }
            return new Registration(slug, name, List.copyOf(hosts), server, port, dbName, options, loginSecret, passwordSecret,
                    baseline.equals("true"), collation, status, migrationLoginSecret, migrationPasswordSecret);
        }

        private static String secretName(String name, String option) {
            if (!SecretNames.isValid(name)) {
                throw new IllegalArgumentException(option + " must be a secret NAME (lower-case letters and digits joined by "
                        + "single hyphens), never a value.");
            }
            return name;
        }
    }
}
