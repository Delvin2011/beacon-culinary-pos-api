package com.beaconculinary.api.support.tenancy;

import com.beaconculinary.api.support.baseline.ThrowawayDatabase;
import com.beaconculinary.api.tenancy.PlatformDatabase;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * T1-2, TESTS ONLY: two customers for multi-mode tests, made once per test run: a throwaway platform database and two
 * throwaway customer databases (alpha, bravo) built the T0-7 way (the B116 baseline), both registered ACTIVE. T1-4: the
 * app logs in to them with an app login made here (read, write and execute only: db_datareader, db_datawriter, GRANT
 * EXECUTE), as it does in production, so every multi-mode test runs with those rights; the tests' own direct access
 * ({@link #direct}) uses the suite's login. Dropped when the test JVM ends (the login after the databases).
 *
 * <p>{@link Initializer} adds the multi-mode settings (platform database, secrets) to a test context; with the suite's
 * database URL known only once the environment is ready, an initializer is the place for it.
 */
public final class MultiCustomerFixture {
    private static final Pattern SERVER = Pattern.compile("^jdbc:sqlserver://([A-Za-z0-9.-]+)(?::(\\d+))?(;.*)?$");
    private static MultiCustomerFixture instance;

    private final ThrowawayDatabase platform;
    private final Map<String, ThrowawayDatabase> customers = new HashMap<>();
    private final Map<String, CurrentCustomer> registered = new HashMap<>();
    private final String suiteUrl;
    private final String username;
    private final String password;
    private final String appLogin = "t14app" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private final String appPassword = "T14a-" + UUID.randomUUID().toString().replace("-", "") + "!";

    private MultiCustomerFixture(String url, String username, String password) throws SQLException {
        this.suiteUrl = url;
        this.username = username;
        this.password = password;
        var server = SERVER.matcher(url);
        if (!server.matches()) {
            throw new IllegalStateException("The test database URL names no plain host and port; multi-mode tests need one");
        }
        var host = server.group(1);
        var port = server.group(2) == null ? 1433 : Integer.parseInt(server.group(2));
        var options = options(server.group(3));
        var made = new ArrayList<ThrowawayDatabase>();
        try {
            platform = ThrowawayDatabase.create(url, username, password, "MultiPlatform", "SQL_Latin1_General_CP1_CI_AS");
            made.add(platform);
            platform.flyway(PlatformDatabase.LOCATION).migrate();
            var platformJdbc = jdbc(platform);
            platformJdbc.execute("CREATE LOGIN [" + appLogin + "] WITH PASSWORD = N'" + appPassword + "'");
            for (var slug : List.of("alpha", "bravo")) {
                var db = ThrowawayDatabase.create(url, username, password, "Multi" + slug, "SQL_Latin1_General_CP1_CI_AS");
                made.add(db);
                db.flyway("classpath:db/migration", "classpath:db/baseline").migrate();
                allowAppLogin(jdbc(db));
                customers.put(slug, db);
                var id = platformJdbc.queryForObject(
                        "INSERT INTO tenants (slug, display_name, status) OUTPUT INSERTED.id VALUES (?, ?, 'ACTIVE')",
                        Long.class, slug, slug);
                platformJdbc.update("INSERT INTO tenant_hosts (tenant_id, host, is_primary) VALUES (?, ?, 1)", id, slug + ".pos.localhost");
                platformJdbc.update("INSERT INTO tenant_databases (tenant_id, server_host, server_port, database_name, "
                                + "connection_options, login_secret_name, password_secret_name, built_from_baseline) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                        id, host, port, db.name(), options, slug + "-db-login", slug + "-db-password");
                registered.put(slug, new CurrentCustomer(id, slug));
            }
        } catch (SQLException | RuntimeException e) {
            for (var db : made) {
                try {
                    db.close();
                } catch (SQLException ignored) {
                    // best effort
                }
            }
            dropAppLogin();
            throw e;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::drop, "multi-customer-fixture-drop"));
    }

    /** The fixture, made on first use from the suite's database settings. */
    public static synchronized MultiCustomerFixture get(Environment environment) {
        if (instance == null) {
            try {
                instance = new MultiCustomerFixture(environment.getRequiredProperty("spring.datasource.url"),
                        environment.getRequiredProperty("spring.datasource.username"),
                        environment.getRequiredProperty("spring.datasource.password"));
            } catch (SQLException e) {
                throw new IllegalStateException("Couldn't make the multi-customer test databases", e);
            }
        }
        return instance;
    }

    /** The fixture once made (tests run after their context, so it is). */
    public static synchronized MultiCustomerFixture get() {
        if (instance == null) {
            throw new IllegalStateException("The multi-customer fixture isn't made yet: use its Initializer");
        }
        return instance;
    }

    public CurrentCustomer customer(String slug) {
        return registered.get(slug);
    }

    /** Direct access to a customer's database, around the application (to arrange and to check). */
    public JdbcTemplate direct(String slug) {
        return jdbc(customers.get(slug));
    }

    public JdbcTemplate platform() {
        return jdbc(platform);
    }

    /** T1-4: the name of the login the app uses for alpha and bravo (not a secret value in tests). */
    public String appLogin() {
        return appLogin;
    }

    /** T1-4: a customer's database as the app login sees it (read, write and execute only). */
    public JdbcTemplate asApp(String slug) {
        return new JdbcTemplate(new DriverManagerDataSource(customers.get(slug).url(), appLogin, appPassword));
    }

    /** T1-4: gives the app login the app's rights in a database (given through the suite's login). */
    public void allowAppLogin(JdbcTemplate database) {
        database.execute("CREATE USER [" + appLogin + "] FOR LOGIN [" + appLogin + "]; "
                + "ALTER ROLE db_datareader ADD MEMBER [" + appLogin + "]; "
                + "ALTER ROLE db_datawriter ADD MEMBER [" + appLogin + "]; "
                + "GRANT EXECUTE TO [" + appLogin + "];");
    }

    Map<String, Object> settings() {
        var settings = new HashMap<String, Object>();
        settings.put("app.tenancy.mode", "multi");
        settings.put("app.tenancy.platform.url", platform.url());
        settings.put("app.tenancy.platform.username", platform.username());
        settings.put("app.tenancy.platform.password", platform.password());
        for (var slug : customers.keySet()) {
            var name = slug.toUpperCase();
            settings.put("SECRET_" + name + "_DB_LOGIN", appLogin);
            settings.put("SECRET_" + name + "_DB_PASSWORD", appPassword);
        }
        return settings;
    }

    private void drop() {
        for (var db : customers.values()) {
            try {
                db.close();
            } catch (SQLException ignored) {
                // best effort at JVM exit
            }
        }
        try {
            platform.close();
        } catch (SQLException ignored) {
            // best effort at JVM exit
        }
        dropAppLogin();
    }

    /** After the databases: dropping them ended the login's sessions. Best effort. */
    private void dropAppLogin() {
        try (var connection = java.sql.DriverManager.getConnection(suiteUrl, username, password);
             var st = connection.createStatement()) {
            st.execute("IF SUSER_ID(N'" + appLogin + "') IS NOT NULL DROP LOGIN [" + appLogin + "]");
        } catch (SQLException ignored) {
            // best effort at JVM exit
        }
    }

    private static JdbcTemplate jdbc(ThrowawayDatabase db) {
        return new JdbcTemplate(new DriverManagerDataSource(db.url(), db.username(), db.password()));
    }

    private static String options(String rest) {
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

    /** Puts the multi-mode settings first in a test context's environment. */
    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var fixture = get(context.getEnvironment());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t1-2-multi-customer", fixture.settings()));
        }
    }
}
