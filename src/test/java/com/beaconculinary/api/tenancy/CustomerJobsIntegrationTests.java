package com.beaconculinary.api.tenancy;

import com.beaconculinary.api.auth.protection.LoginAttemptRetentionJob;
import com.beaconculinary.api.common.logging.RequestLogContext;
import com.beaconculinary.api.sessions.SessionService;
import com.beaconculinary.api.sessions.SessionSweepJob;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.baseline.ThrowawayDatabase;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.beaconculinary.api.tenancy.jobs.CustomerJobs;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1-2: background work in multi-customer mode. A job runs once per ACTIVE customer inside that customer's context (and
 * its slug as tenantId), a failure for one customer doesn't stop the next, the real jobs (login-attempt retention,
 * session sweep) work per customer, and GET /health probes the platform database and reports the customer pools as
 * counts, without connecting to any customer database. T1-6: a customer that can't be served (suspended, or its
 * database behind) is skipped with one WARN and resumes with one INFO, not a log line per run.
 */
@ExtendWith(OutputCaptureExtension.class)
class CustomerJobsIntegrationTests extends MultiCustomerIntegrationTest {
    @Autowired
    private CustomerJobs customerJobs;
    @Autowired
    private CustomerDirectory directory;
    @Autowired
    private LoginAttemptRetentionJob retentionJob;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private Environment environment;

    @Test
    void aJob_runsOncePerActiveCustomer_inThatCustomersContext() {
        var seen = new ArrayList<String>();
        var failed = customerJobs.forEachActiveCustomer("t1-2-test", () ->
                seen.add(CustomerContext.require().slug() + "/" + MDC.get(RequestLogContext.TENANT_ID)));
        assertThat(failed).isZero();
        assertThat(seen).containsExactly("alpha/alpha", "bravo/bravo");
        assertThat(CustomerContext.current()).as("nothing left on the scheduler's thread").isEmpty();
    }

    @Test
    void aSuspendedCustomer_isSkippedWithOneWarn_andResumesWithOneInfo(CapturedOutput output) {
        var platform = MultiCustomerFixture.get().platform();
        platform.update("UPDATE tenants SET status = 'SUSPENDED' WHERE slug = 'bravo'");
        try {
            directory.refresh();
            for (int run = 0; run < 3; run++) {
                var seen = new ArrayList<String>();
                assertThat(customerJobs.forEachActiveCustomer("t1-6-test", () -> seen.add(CustomerContext.require().slug())))
                        .as("skipping isn't a failure").isZero();
                assertThat(seen).containsExactly("alpha");
            }
            assertThat(count(output, "jobs: customer bravo is skipped by every job, as it is SUSPENDED")).isEqualTo(1);
        } finally {
            platform.update("UPDATE tenants SET status = 'ACTIVE' WHERE slug = 'bravo'");
            directory.refresh();
        }
        for (int run = 0; run < 2; run++) {
            var seen = new ArrayList<String>();
            customerJobs.forEachActiveCustomer("t1-6-test", () -> seen.add(CustomerContext.require().slug()));
            assertThat(seen).containsExactly("alpha", "bravo");
        }
        assertThat(count(output, "jobs: customer bravo is served again")).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("job t1-6-test failed");
    }

    /**
     * T1-6: charlie's database is at V115, behind the app. Every run skips it (the pool is asked again after each 2 s
     * recheck, moved on by the test clock): one WARN from the jobs and one ERROR from the pool, not one per run; alpha
     * and bravo run. Once updated, it is served again with one INFO, without a restart.
     */
    @Test
    void aCustomerWhoseDatabaseIsBehind_isSkippedWithOneWarn_andResumesOnceUpdated(CapturedOutput output) throws Exception {
        var fixture = MultiCustomerFixture.get();
        var url = environment.getRequiredProperty("spring.datasource.url");
        var username = environment.getRequiredProperty("spring.datasource.username");
        var password = environment.getRequiredProperty("spring.datasource.password");
        var platform = fixture.platform();
        try (var db = ThrowawayDatabase.create(url, username, password, "JobsBehind", "SQL_Latin1_General_CP1_CI_AS")) {
            Flyway.configure().dataSource(db.url(), username, password).locations("classpath:db/migration").target("115")
                    .cleanDisabled(true).load().migrate();
            fixture.allowAppLogin(new JdbcTemplate(new DriverManagerDataSource(db.url(), username, password)));
            var id = platform.queryForObject("INSERT INTO tenants (slug, display_name, status) OUTPUT INSERTED.id "
                    + "VALUES ('charlie', 'charlie', 'ACTIVE')", Long.class);
            try {
                // On alpha's server, with alpha's app login (its rights were given above).
                platform.update("INSERT INTO tenant_databases (tenant_id, server_host, server_port, database_name, connection_options, "
                        + "login_secret_name, password_secret_name, built_from_baseline) SELECT ?, server_host, server_port, ?, "
                        + "connection_options, login_secret_name, password_secret_name, 0 FROM tenant_databases WHERE tenant_id = ?",
                        id, db.name(), fixture.customer("alpha").id());
                directory.refresh();
                var start = Instant.now();
                for (int run = 0; run < 3; run++) {
                    clock.setInstant(start.plusSeconds(3L * run));
                    var seen = new ArrayList<String>();
                    assertThat(customerJobs.forEachActiveCustomer("t1-6-test", () -> seen.add(CustomerContext.require().slug())))
                            .isZero();
                    assertThat(seen).containsExactly("alpha", "bravo");
                }
                assertThat(count(output, "jobs: customer charlie is skipped by every job, as its database is behind")).isEqualTo(1);
                assertThat(count(output, "tenancy: customer charlie database " + db.name() + " is at schema version 115, behind"))
                        .as("the pool's ERROR, once for this version").isEqualTo(1);

                Flyway.configure().dataSource(db.url(), username, password).locations("classpath:db/migration")
                        .cleanDisabled(true).load().migrate();
                clock.setInstant(start.plusSeconds(20));
                var seen = new ArrayList<String>();
                customerJobs.forEachActiveCustomer("t1-6-test", () -> seen.add(CustomerContext.require().slug()));
                assertThat(seen).containsExactly("alpha", "bravo", "charlie");
                assertThat(count(output, "jobs: customer charlie is served again")).isEqualTo(1);
            } finally {
                platform.update("DELETE FROM tenant_databases WHERE tenant_id = ?", id);
                platform.update("DELETE FROM tenants WHERE id = ?", id);
                clock.reset();
                directory.refresh();
            }
        }
    }

    private static int count(CapturedOutput output, String text) {
        var all = output.getAll();
        var found = 0;
        for (int at = all.indexOf(text); at >= 0; at = all.indexOf(text, at + text.length())) {
            found++;
        }
        return found;
    }

    @Test
    void aFailureForOneCustomer_doesNotStopTheNext_andIsLoggedWithItsSlug(CapturedOutput output) {
        var seen = new ArrayList<String>();
        var failed = customerJobs.forEachActiveCustomer("t1-2-test", () -> {
            var slug = CustomerContext.require().slug();
            if (slug.equals("alpha")) {
                throw new IllegalStateException("alpha's run breaks");
            }
            seen.add(slug);
        });
        assertThat(failed).isEqualTo(1);
        assertThat(seen).containsExactly("bravo");
        assertThat(output.getAll()).contains("job t1-2-test failed for customer alpha; the other customers' runs go on")
                .contains("alpha's run breaks");
    }

    @Test
    void theRealJobs_workInEachCustomersDatabase() {
        var fixture = MultiCustomerFixture.get();
        var marker = "t12-old-" + UUID.randomUUID().toString().substring(0, 8);
        for (var slug : List.of("alpha", "bravo")) {
            fixture.direct(slug).update("INSERT INTO login_attempts (attempted_at, method, identifier, outcome) "
                    + "VALUES (DATEADD(day, -400, SYSUTCDATETIME()), 'PASSWORD', ?, 'BAD_CREDENTIAL')", marker);
        }
        retentionJob.scheduledPurge();
        for (var slug : List.of("alpha", "bravo")) {
            assertThat(fixture.direct(slug).queryForObject("SELECT COUNT(*) FROM login_attempts WHERE identifier = ?",
                    Integer.class, marker)).as(slug + "'s old attempt purged").isZero();
        }
        // The scheduled sweep bean is off in tests; the job itself runs per customer without error.
        new SessionSweepJob(sessionService, customerJobs).sweep();
    }

    @Test
    void health_probesThePlatformDatabase_andCountsTheCustomerPools() throws Exception {
        mvc.perform(get("/health").header("Host", "localhost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.customerPools.open").isNumber())
                .andExpect(jsonPath("$.customerPools.failing").value(0));
    }
}
