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
import java.util.regex.Pattern;

/**
 * T1-2, TESTS ONLY: two customers for multi-mode tests, made once per test run: a throwaway platform database and two
 * throwaway customer databases (alpha, bravo) built the T0-7 way (the B116 baseline), both registered ACTIVE. The
 * customers log in with the suite's own login (their secrets point at it). Dropped when the test JVM ends.
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
    private final String username;
    private final String password;

    private MultiCustomerFixture(String url, String username, String password) throws SQLException {
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
            for (var slug : List.of("alpha", "bravo")) {
                var db = ThrowawayDatabase.create(url, username, password, "Multi" + slug, "SQL_Latin1_General_CP1_CI_AS");
                made.add(db);
                db.flyway("classpath:db/migration", "classpath:db/baseline").migrate();
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

    Map<String, Object> settings() {
        var settings = new HashMap<String, Object>();
        settings.put("app.tenancy.mode", "multi");
        settings.put("app.tenancy.platform.url", platform.url());
        settings.put("app.tenancy.platform.username", platform.username());
        settings.put("app.tenancy.platform.password", platform.password());
        for (var slug : customers.keySet()) {
            var name = slug.toUpperCase();
            settings.put("SECRET_" + name + "_DB_LOGIN", username);
            settings.put("SECRET_" + name + "_DB_PASSWORD", password);
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
