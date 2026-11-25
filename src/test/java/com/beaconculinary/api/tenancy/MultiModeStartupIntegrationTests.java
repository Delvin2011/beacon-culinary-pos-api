package com.beaconculinary.api.tenancy;

import com.beaconculinary.api.BeaconCulinaryApiApplication;
import com.beaconculinary.api.support.baseline.ThrowawayDatabase;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.beaconculinary.api.tenancy.context.NoCurrentCustomerException;
import com.beaconculinary.api.tenancy.routing.CustomerPools;
import com.beaconculinary.api.tenancy.routing.CustomerRoutingDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1-2: in multi-customer mode the application starts without connecting to any customer database (there is no
 * customer at start-up), here with both customers' databases unreachable. Afterwards a database access with no
 * customer is the specific error, never a fallback; and (T1-3) the backend's own address serves /health only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ExtendWith(OutputCaptureExtension.class)
class MultiModeStartupIntegrationTests {
    /** Never a real password: only recognisable in captured output. */
    private static final String SECRET_SENTINEL = "Sentinel-Secret-91b2";

    @Autowired
    private Environment environment;

    @Test
    void startsWithEveryCustomerDatabaseOffline_andNeverFallsBackToADefaultDatabase(CapturedOutput output) throws Exception {
        var url = environment.getRequiredProperty("spring.datasource.url");
        var username = environment.getRequiredProperty("spring.datasource.username");
        var password = environment.getRequiredProperty("spring.datasource.password");
        Assumptions.assumeTrue(ThrowawayDatabase.canCreate(url, username, password), "the test login can't create databases");
        try (var platform = ThrowawayDatabase.create(url, username, password, "MultiStart", "SQL_Latin1_General_CP1_CI_AS")) {
            platform.flyway(PlatformDatabase.LOCATION).migrate();
            var platformJdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    platform.url(), platform.username(), platform.password()));
            // Both customers' databases are on a port nothing listens on.
            var alpha = register(platformJdbc, "alpha");
            register(platformJdbc, "bravo");

            try (var app = start(platform)) {
                assertThat(app.getBean("dataSource")).isInstanceOf(CustomerRoutingDataSource.class);
                var pools = app.getBean(CustomerPools.class);
                assertThat(pools.poolsCreated()).as("no customer database touched at start-up").isZero();

                var dialect = app.getBean(EntityManagerFactory.class).unwrap(SessionFactoryImplementor.class)
                        .getJdbcServices().getDialect();
                assertThat(dialect.getVersion().getMajor()).as("the configured version, not one read from a database").isEqualTo(12);
                assertThat(output.getAll()).contains("tenancy multi: customer database migrations are not run at start-up")
                        .contains("tenancy multi: customer directory loaded, 2 customer(s), 2 active");

                // No customer: the specific error, and no pool made.
                var jdbc = app.getBean(JdbcTemplate.class);
                assertThatThrownBy(() -> jdbc.queryForObject("SELECT 1", Integer.class))
                        .hasRootCauseInstanceOf(NoCurrentCustomerException.class);
                assertThat(pools.poolsCreated()).isZero();

                // As alpha: its own (unreachable) database is tried, nothing else.
                assertThatThrownBy(() -> CustomerContext.runAs(alpha, () -> jdbc.queryForObject("SELECT 1", Integer.class)))
                        .isNotInstanceOf(NoCurrentCustomerException.class);
                assertThat(pools.hasPool("alpha")).isTrue();
                assertThat(pools.hasPool("bravo")).isFalse();

                // T1-3: at the backend's own address (localhost) only /health answers; no customer data is reachable.
                var port = app.getEnvironment().getRequiredProperty("local.server.port");
                var client = HttpClient.newHttpClient();
                var board = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/public/board/today"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(board.statusCode()).isEqualTo(404);
                assertThat(board.body()).isEmpty();
                var health = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(health.statusCode()).as("platform health").isEqualTo(200);
                assertThat(pools.hasPool("bravo")).isFalse();
                assertThat(output.getAll()).contains("multi mode without customer-bound tokens: local development only");
            }
            assertThat(output.getAll().contains(SECRET_SENTINEL)).as("a secret value appears in the output").isFalse();
            assertThat(output.getAll().contains(password)).as("the database password appears in the output").isFalse();
        }
    }

    private static CurrentCustomer register(JdbcTemplate platformJdbc, String slug) {
        var id = platformJdbc.queryForObject("INSERT INTO tenants (slug, display_name, status) OUTPUT INSERTED.id VALUES (?, ?, 'ACTIVE')",
                Long.class, slug, slug);
        platformJdbc.update("INSERT INTO tenant_hosts (tenant_id, host, is_primary) VALUES (?, ?, 1)", id, slug + ".pos.localhost");
        platformJdbc.update("INSERT INTO tenant_databases (tenant_id, server_host, server_port, database_name, connection_options, "
                        + "login_secret_name, password_secret_name, built_from_baseline) VALUES (?, '127.0.0.1', 1, ?, 'loginTimeout=2', ?, ?, 1)",
                id, slug, slug + "-db-login", slug + "-db-password");
        return new CurrentCustomer(id, slug);
    }

    private ConfigurableApplicationContext start(ThrowawayDatabase platform) {
        var values = new HashMap<String, Object>(Map.of(
                "app.tenancy.mode", "multi",
                "app.tenancy.platform.url", platform.url(),
                "app.tenancy.platform.username", platform.username(),
                "app.tenancy.platform.password", platform.password(),
                "SECRET_ALPHA_DB_LOGIN", "alpha_login",
                "SECRET_ALPHA_DB_PASSWORD", SECRET_SENTINEL,
                "SECRET_BRAVO_DB_LOGIN", "bravo_login",
                "SECRET_BRAVO_DB_PASSWORD", SECRET_SENTINEL,
                "app.tenancy.customer-pool.connection-timeout", "3s",
                "server.port", "0"));
        return new SpringApplicationBuilder(BeaconCulinaryApiApplication.class)
                .web(WebApplicationType.SERVLET)
                .initializers(app -> app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t1-2-test", values)))
                .run();
    }
}
