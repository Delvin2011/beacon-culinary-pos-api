package com.beaconculinary.api.support.baseline;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * T0-7: a database that a test creates on the test server and drops afterwards, for comparing a baseline-built
 * schema with a fully migrated one. Uses the test datasource's server and login (which needs CREATE DATABASE;
 * {@link #canCreate} tells). Names always contain "Test", like every database the suite touches.
 */
public final class ThrowawayDatabase implements AutoCloseable {
    private static final Pattern DATABASE_NAME = Pattern.compile("(?i)databaseName=([^;]*)");

    private final String serverUrl;
    private final String username;
    private final String password;
    private final String name;

    private ThrowawayDatabase(String serverUrl, String username, String password, String name) {
        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
        this.name = name;
    }

    private static final Pattern COLLATION = Pattern.compile("[A-Za-z0-9_]+");

    /** Creates an empty database named T07{label}{random}Test on the server of {@code testUrl} (its default collation). */
    public static ThrowawayDatabase create(String testUrl, String username, String password, String label) throws SQLException {
        return create(testUrl, username, password, label, null);
    }

    /** As {@link #create(String, String, String, String)}, with this collation (null: the server's default). */
    public static ThrowawayDatabase create(String testUrl, String username, String password, String label, String collation)
            throws SQLException {
        if (collation != null && !COLLATION.matcher(collation).matches()) {
            throw new IllegalArgumentException("Not a collation name: " + collation);
        }
        var name = "T07" + label + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "Test";
        var db = new ThrowawayDatabase(withDatabase(testUrl, "master"), username, password, name);
        try (var connection = db.master(); var st = connection.createStatement()) {
            st.execute("CREATE DATABASE [" + name + "]" + (collation == null ? "" : " COLLATE " + collation));
        }
        return db;
    }

    /** Whether the login may create databases (otherwise the tests that need one skip themselves). */
    public static boolean canCreate(String testUrl, String username, String password) {
        try (var connection = DriverManager.getConnection(withDatabase(testUrl, "master"), username, password);
             var st = connection.createStatement();
             var rs = st.executeQuery("SELECT HAS_PERMS_BY_NAME(NULL, NULL, 'CREATE ANY DATABASE')")) {
            return rs.next() && rs.getInt(1) == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    public String name() {
        return name;
    }

    public String url() {
        return withDatabase(serverUrl, name);
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url(), username, password);
    }

    /** Flyway for this database with the given locations. Clean stays disabled. */
    public Flyway flyway(String... locations) {
        return Flyway.configure().dataSource(url(), username, password).locations(locations).cleanDisabled(true)
                .baselineOnMigrate(false).load();
    }

    private Connection master() throws SQLException {
        return DriverManager.getConnection(serverUrl, username, password);
    }

    @Override
    public void close() throws SQLException {
        try (var connection = master(); var st = connection.createStatement()) {
            st.execute("IF DB_ID('" + name + "') IS NOT NULL BEGIN ALTER DATABASE [" + name
                    + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [" + name + "]; END");
        }
    }

    static String withDatabase(String url, String database) {
        var matcher = DATABASE_NAME.matcher(url);
        if (matcher.find()) {
            return matcher.replaceFirst("databaseName=" + database);
        }
        return url + (url.endsWith(";") ? "" : ";") + "databaseName=" + database;
    }
}
