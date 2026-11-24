package com.beaconculinary.api.tenancy;

import com.beaconculinary.api.auth.protection.LoginAttemptRetentionJob;
import com.beaconculinary.api.common.logging.RequestLogContext;
import com.beaconculinary.api.sessions.SessionService;
import com.beaconculinary.api.sessions.SessionSweepJob;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.beaconculinary.api.tenancy.jobs.CustomerJobs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

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
 * counts, without connecting to any customer database.
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
    void aSuspendedCustomer_isLeftOut() {
        var platform = MultiCustomerFixture.get().platform();
        platform.update("UPDATE tenants SET status = 'SUSPENDED' WHERE slug = 'bravo'");
        try {
            directory.refresh();
            var seen = new ArrayList<String>();
            customerJobs.forEachActiveCustomer("t1-2-test", () -> seen.add(CustomerContext.require().slug()));
            assertThat(seen).containsExactly("alpha");
        } finally {
            platform.update("UPDATE tenants SET status = 'ACTIVE' WHERE slug = 'bravo'");
            directory.refresh();
        }
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
