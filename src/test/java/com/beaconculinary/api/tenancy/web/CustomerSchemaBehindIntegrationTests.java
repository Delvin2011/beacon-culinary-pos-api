package com.beaconculinary.api.tenancy.web;

import com.beaconculinary.api.ops.MigrateCustomersCommand;
import com.beaconculinary.api.support.baseline.ThrowawayDatabase;
import com.beaconculinary.api.support.tenancy.CustomerMvc;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.routing.AppSchemaVersion;
import com.beaconculinary.api.tenancy.secrets.MissingSecretException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * T1-4, end to end in multi mode: a customer whose database is behind the app (V115) is refused with 503
 * CUSTOMER_UNAVAILABLE and an ERROR naming it and both versions, while another customer is served; after the
 * migration command has updated it, it is served again within the recheck time (2 s here), without a restart.
 */
@ExtendWith(OutputCaptureExtension.class)
class CustomerSchemaBehindIntegrationTests extends MultiCustomerIntegrationTest {
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private Environment environment;

    @Test
    void aCustomerBehind_isRefused_untilTheCommandRuns_thenServedWithoutARestart_andOthersAreUnaffected(CapturedOutput output)
            throws Exception {
        var fixture = MultiCustomerFixture.get();
        var url = environment.getRequiredProperty("spring.datasource.url");
        var username = environment.getRequiredProperty("spring.datasource.username");
        var password = environment.getRequiredProperty("spring.datasource.password");
        var latest = AppSchemaVersion.latest().getVersion();
        var platform = fixture.platform();
        try (var db = ThrowawayDatabase.create(url, username, password, "Behind", "SQL_Latin1_General_CP1_CI_AS")) {
            Flyway.configure().dataSource(db.url(), username, password).locations("classpath:db/migration").target("115")
                    .cleanDisabled(true).load().migrate();
            fixture.allowAppLogin(new JdbcTemplate(new DriverManagerDataSource(db.url(), username, password)));
            var id = platform.queryForObject("INSERT INTO tenants (slug, display_name, status) OUTPUT INSERTED.id "
                    + "VALUES ('charlie', 'charlie', 'ACTIVE')", Long.class);
            try {
                platform.update("INSERT INTO tenant_hosts (tenant_id, host, is_primary) VALUES (?, 'charlie.pos.localhost', 1)", id);
                // Where alpha's database is and its app login (the app login's rights were given above).
                platform.update("INSERT INTO tenant_databases (tenant_id, server_host, server_port, database_name, connection_options, "
                        + "login_secret_name, password_secret_name, built_from_baseline, migration_login_secret_name, "
                        + "migration_password_secret_name) SELECT ?, server_host, server_port, ?, connection_options, login_secret_name, "
                        + "password_secret_name, 0, 'charlie-db-migration-login', 'charlie-db-migration-password' FROM tenant_databases "
                        + "WHERE tenant_id = ?", id, db.name(), fixture.customer("alpha").id());
                var charlie = CustomerMvc.at(context, "charlie");
                var alpha = CustomerMvc.at(context, "alpha");

                var refused = await(charlie, response -> response.getStatus() == 503);
                assertThat(refused.getContentAsString()).contains("\"code\":\"" + CustomerResolutionFilter.CUSTOMER_UNAVAILABLE + "\"");
                assertThat(output.getAll()).contains("tenancy: customer charlie database " + db.name() + " is at schema version 115, "
                        + "behind the app's " + latest + ": not served");
                assertThat(alpha.perform(get("/public/board/today")).andReturn().getResponse().getStatus())
                        .as("another customer is served meanwhile").isEqualTo(200);

                var printed = new ByteArrayOutputStream();
                var print = new PrintStream(printed, true, StandardCharsets.UTF_8);
                var secrets = Map.of("charlie-db-migration-login", username, "charlie-db-migration-password", password);
                var code = new MigrateCustomersCommand(environment.getRequiredProperty("app.tenancy.platform.url"),
                        environment.getRequiredProperty("app.tenancy.platform.username"),
                        environment.getRequiredProperty("app.tenancy.platform.password"), name -> {
                            var value = secrets.get(name);
                            if (value == null) {
                                throw new MissingSecretException(name, "the test's secrets");
                            }
                            return value;
                        }, print, print, Clock.systemUTC(), "test-operator").run(new String[]{"--customer", "charlie"});
                assertThat(code).as(printed.toString(StandardCharsets.UTF_8)).isZero();
                assertThat(printed.toString(StandardCharsets.UTF_8)).containsPattern("charlie\\s+115\\s+" + latest + "\\s+SUCCEEDED");

                await(charlie, response -> response.getStatus() == 200);
                assertThat(output.getAll()).contains("tenancy: customer charlie pool opened");
            } finally {
                platform.update("DELETE FROM provisioning_runs WHERE tenant_id = ?", id);
                platform.update("DELETE FROM tenant_databases WHERE tenant_id = ?", id);
                platform.update("DELETE FROM tenant_hosts WHERE tenant_id = ?", id);
                platform.update("DELETE FROM tenants WHERE id = ?", id);
            }
        }
    }

    /** The public board at this customer, asked until the answer matches (the directory and the recheck take seconds). */
    private static MockHttpServletResponse await(MockMvc mvc, Predicate<MockHttpServletResponse> wanted) throws Exception {
        var deadline = System.nanoTime() + 20_000_000_000L;
        while (true) {
            var response = mvc.perform(get("/public/board/today")).andReturn().getResponse();
            if (wanted.test(response) || System.nanoTime() > deadline) {
                assertThat(wanted.test(response)).as("HTTP " + response.getStatus() + " " + response.getContentAsString()).isTrue();
                return response;
            }
            Thread.sleep(250);
        }
    }
}
