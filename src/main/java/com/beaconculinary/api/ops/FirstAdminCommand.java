package com.beaconculinary.api.ops;

import com.beaconculinary.api.auth.invite.InviteLinks;
import com.beaconculinary.api.auth.invite.InviteTokens;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * T0-6a: the operator's first-admin command (runbook: docs/2026-11-21-first-admin-and-invites.md).
 *
 * <p>Creates the customer's first administrator with no password or PIN, and a FIRST_ADMIN invite
 * link valid for 72 hours, in one transaction. The link is printed to this terminal (stdout) once
 * and nowhere else: this class has no logger, never starts Spring, and the web app never calls it.
 * It is the one file allowed to print to the console (NoConsolePrintingTest).
 *
 * <pre>
 * java -cp app.jar -Dloader.main=com.beaconculinary.api.ops.FirstAdminCommand \
 *      org.springframework.boot.loader.launch.PropertiesLauncher \
 *      --name "Full Name" --email person@example.com [--base-url https://pos.example.com]
 *      [--force --reason "why"]
 * </pre>
 *
 * <p>Database: SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD from the
 * environment (never printed). Base URL: --base-url (the customer's own address), else no link (the
 * token is printed with how to build the link).
 *
 * <p>Refuses when an active, non-demo ADMIN who has set a password exists, unless --force with a
 * --reason, which is stored on the invite (operator_forced, operator_reason). Running it again for
 * the same email while that admin hasn't finished setting up replaces their link.
 *
 * <p>Exit codes: 0 done, 2 usage, 3 refused, 4 failed.
 */
public final class FirstAdminCommand {
    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int REFUSED = 3;
    public static final int FAILED = 4;

    /** The first-admin link always lasts 72 hours (the site setting may not be set up yet). */
    public static final int EXPIRY_HOURS = 72;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String USAGE_TEXT = """
            Usage: --name "Full Name" --email person@example.com [--base-url https://pos.example.com]
                   [--force --reason "why another first admin is needed"]
            Database from SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD.""";

    /** Opens the database connection; replaced in tests. */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    private final ConnectionSource connections;
    private final PrintStream out;
    private final PrintStream err;
    private final Clock clock;

    public FirstAdminCommand(ConnectionSource connections, PrintStream out,
                             PrintStream err, Clock clock) {
        this.connections = connections;
        this.out = out;
        this.err = err;
        this.clock = clock;
    }

    public static void main(String[] args) {
        var env = System.getenv();
        if (env.get("SPRING_DATASOURCE_URL") == null || env.get("SPRING_DATASOURCE_URL").isBlank()) {
            System.err.println("SPRING_DATASOURCE_URL is not set (with SPRING_DATASOURCE_USERNAME and _PASSWORD).");
            System.exit(USAGE);
        }
        ConnectionSource source = () -> DriverManager.getConnection(env.get("SPRING_DATASOURCE_URL"),
                env.get("SPRING_DATASOURCE_USERNAME"), env.get("SPRING_DATASOURCE_PASSWORD"));
        var code = new FirstAdminCommand(source, System.out, System.err, Clock.systemUTC()).run(args);
        System.exit(code);
    }

    /** Runs the command; returns the exit code. */
    public int run(String[] args) {
        Map<String, String> options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE_TEXT);
            return USAGE;
        }
        var name = options.get("name") == null ? "" : options.get("name").trim();
        var email = options.get("email") == null ? "" : options.get("email").trim().toLowerCase(Locale.ROOT);
        var force = options.containsKey("force");
        var reason = options.get("reason") == null ? null : options.get("reason").trim();
        if (name.isEmpty() || name.length() > 255) {
            return usage("--name is required (at most 255 characters).");
        }
        if (!EMAIL.matcher(email).matches() || email.length() > 255) {
            return usage("--email must be a valid email address.");
        }
        if (force && (reason == null || reason.isEmpty() || reason.length() > 500)) {
            return usage("--force needs --reason \"...\" (at most 500 characters), stored on the invite.");
        }
        if (!force && reason != null) {
            return usage("--reason is only used with --force.");
        }
        String baseUrl;
        try {
            var given = options.get("base-url");
            baseUrl = InviteLinks.normalizeBase(given, "--base-url");
        } catch (IllegalStateException e) {
            return usage(e.getMessage());
        }

        try (var connection = connections.open()) {
            return create(connection, name, email, force, reason, baseUrl);
        } catch (SQLException e) {
            // The driver's message can name the server but never the password; the SQL state helps the operator.
            err.println("Failed: database error (SQL state " + e.getSQLState() + "): " + e.getMessage());
            return FAILED;
        } catch (RuntimeException e) {
            err.println("Failed: " + e.getClass().getSimpleName());
            return FAILED;
        }
    }

    private int create(Connection connection, String name, String email, boolean force, String reason, String baseUrl)
            throws SQLException {
        if (!schemaReady(connection)) {
            err.println("Refused: the database is not migrated to V116 yet. Start the app once (it applies the "
                    + "migrations), then run this again.");
            return REFUSED;
        }
        // One first-admin run at a time. A session lock: with auto-commit off, SQL Server opens the
        // transaction only at the first data statement, so a transaction-owned lock can't be taken first.
        if (!lock(connection)) {
            err.println("Refused: another first-admin run is in progress. Try again shortly.");
            return REFUSED;
        }
        try {
            connection.setAutoCommit(false);
            try {
                var result = createInTransaction(connection, name, email, force, reason, baseUrl);
                if (result == OK) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } finally {
            unlock(connection);
        }
    }

    private static boolean lock(Connection connection) throws SQLException {
        try (var call = connection.prepareStatement(
                "DECLARE @r INT; EXEC @r = sp_getapplock @Resource = 'beacon-first-admin', @LockMode = 'Exclusive', "
                        + "@LockOwner = 'Session', @LockTimeout = 10000; SELECT @r AS [result]");
             var rs = call.executeQuery()) {
            return rs.next() && rs.getInt(1) >= 0;
        }
    }

    private static void unlock(Connection connection) throws SQLException {
        try (var call = connection.prepareStatement(
                "EXEC sp_releaseapplock @Resource = 'beacon-first-admin', @LockOwner = 'Session'")) {
            call.execute();
        }
    }

    private int createInTransaction(Connection connection, String name, String email, boolean force, String reason,
                                    String baseUrl) throws SQLException {
        long realAdmins;
        try (var count = connection.prepareStatement("""
                SELECT COUNT(*) FROM [users] WITH (UPDLOCK, HOLDLOCK)
                WHERE [role] = 'ADMIN' AND [active] = 1 AND [is_demo] = 0 AND [password] IS NOT NULL""");
             var rs = count.executeQuery()) {
            rs.next();
            realAdmins = rs.getLong(1);
        }
        if (realAdmins > 0 && !force) {
            err.println("Refused: " + realAdmins + " active admin(s) already set up. Use the staff screen to add "
                    + "admins, or --force --reason \"...\" if none of them can sign in (see the runbook).");
            return REFUSED;
        }

        Long userId = null;
        try (var find = connection.prepareStatement(
                "SELECT [id], [role], [active], [is_demo], [password] FROM [users] WITH (UPDLOCK, HOLDLOCK) WHERE LOWER([email]) = ?")) {
            find.setString(1, email);
            try (var rs = find.executeQuery()) {
                if (rs.next()) {
                    var reusable = "ADMIN".equals(rs.getString("role")) && rs.getBoolean("active")
                            && !rs.getBoolean("is_demo") && rs.getString("password") == null;
                    if (!reusable) {
                        err.println("Refused: that email belongs to an existing account that isn't an unfinished "
                                + "first admin. Use another email, or manage that account in the staff screen.");
                        return REFUSED;
                    }
                    userId = rs.getLong("id");
                }
            }
        }

        var now = LocalDateTime.now(clock.withZone(java.time.ZoneOffset.UTC));
        if (userId == null) {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO [users] ([name], [email], [password], [role], [pin_hash], [active])
                    OUTPUT INSERTED.[id] VALUES (?, ?, NULL, 'ADMIN', NULL, 1)""")) {
                insert.setNString(1, name);
                insert.setNString(2, email);
                try (var rs = insert.executeQuery()) {
                    rs.next();
                    userId = rs.getLong(1);
                }
            }
        } else {
            try (var revoke = connection.prepareStatement("""
                    UPDATE [user_invites] SET [revoked_at] = ?, [revoke_reason] = 'REPLACED'
                    WHERE [user_id] = ? AND [used_at] IS NULL AND [revoked_at] IS NULL""")) {
                revoke.setTimestamp(1, Timestamp.valueOf(now));
                revoke.setLong(2, userId);
                revoke.executeUpdate();
            }
        }

        var token = InviteTokens.newToken();
        var expiresAt = now.plusHours(EXPIRY_HOURS);
        long inviteId;
        try (var insert = connection.prepareStatement("""
                INSERT INTO [user_invites] ([user_id], [purpose], [credentials], [token_hash], [created_via],
                                            [operator_forced], [operator_reason], [created_at], [expires_at])
                OUTPUT INSERTED.[id]
                VALUES (?, 'FIRST_ADMIN', 'BOTH', ?, 'OPERATOR', ?, ?, ?, ?)""")) {
            insert.setLong(1, userId);
            insert.setString(2, InviteTokens.hash(token));
            insert.setBoolean(3, force);
            insert.setNString(4, force ? reason : null);
            insert.setTimestamp(5, Timestamp.valueOf(now));
            insert.setTimestamp(6, Timestamp.valueOf(expiresAt));
            try (var rs = insert.executeQuery()) {
                rs.next();
                inviteId = rs.getLong(1);
            }
        }

        out.println("First admin ready: user " + userId + ", invite " + inviteId + ", expires " + expiresAt + " UTC"
                + (force ? " (forced)" : "") + ".");
        var link = InviteLinks.link(baseUrl, token);
        if (link != null) {
            out.println("Send this link to " + email + " over a private channel. It works once:");
            out.println(link);
        } else {
            out.println("No --base-url given: the link is <frontend address>/invite#t=<token>. Token (works once):");
            out.println(token);
        }
        out.println("This is the only time the link is shown. Lost it? Run this again for the same email.");
        return OK;
    }

    private static boolean schemaReady(Connection connection) throws SQLException {
        try (var check = connection.prepareStatement(
                "SELECT CASE WHEN OBJECT_ID('user_invites') IS NOT NULL AND COL_LENGTH('users', 'is_demo') IS NOT NULL THEN 1 ELSE 0 END");
             var rs = check.executeQuery()) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }

    private int usage(String message) {
        err.println(message);
        err.println(USAGE_TEXT);
        return USAGE;
    }

    private static Map<String, String> parse(String[] args) {
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            switch (arg) {
                case "--force" -> options.put("force", "true");
                case "--name", "--email", "--reason", "--base-url" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(arg + " needs a value.");
                    }
                    options.put(arg.substring(2), args[++i]);
                }
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        return options;
    }
}
