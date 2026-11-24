package com.beaconculinary.api.tenancy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T1-1: the platform database (multi mode only): its own small connection pool, separate from the customer data
 * source, and its own migrations (classpath:db/platform), applied when this bean is made. {@link TenancyConfig} makes
 * the customer data source depend on this bean, so the platform migrations finish before anything touches a customer
 * database.
 *
 * <p>Deliberately not a {@code javax.sql.DataSource} bean: a second DataSource bean would switch off Spring Boot's
 * customer data source (and with it JPA and the customer Flyway).
 */
public class PlatformDatabase implements DisposableBean {
    public static final String LOCATION = "classpath:db/platform";
    private static final Logger log = LoggerFactory.getLogger(PlatformDatabase.class);

    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;
    private final String version;

    public PlatformDatabase(TenancyProperties properties) {
        var problems = properties.problemsForMultiMode();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("app.tenancy.mode is multi, but the platform database settings are incomplete: "
                    + String.join("; ", problems));
        }
        var platform = properties.getPlatform();
        var config = new HikariConfig();
        config.setPoolName("platform");
        config.setJdbcUrl(platform.getUrl());
        config.setUsername(platform.getUsername());
        config.setPassword(platform.getPassword());
        config.setMaximumPoolSize(platform.getPoolSize());
        config.setMinimumIdle(Math.min(1, platform.getPoolSize()));
        this.pool = new HikariDataSource(config);
        try {
            var flyway = Flyway.configure().dataSource(pool).locations(LOCATION)
                    .cleanDisabled(true).baselineOnMigrate(false).load();
            var result = flyway.migrate();
            var current = flyway.info().current();
            this.version = current == null ? "none" : current.getVersion().getVersion();
            log.atInfo().addKeyValue("platformVersion", version).addKeyValue("migrationsApplied", result.migrationsExecuted)
                    .log("tenancy multi: platform database at version {} ({} migration(s) applied now)", version,
                            result.migrationsExecuted);
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }
        this.jdbc = new JdbcTemplate(pool);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** The platform schema's version after startup, e.g. "1". */
    public String version() {
        return version;
    }

    @Override
    public void destroy() {
        pool.close();
    }
}
