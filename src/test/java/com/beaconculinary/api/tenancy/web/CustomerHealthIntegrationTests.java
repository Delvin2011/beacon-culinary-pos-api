package com.beaconculinary.api.tenancy.web;

import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * T1-3: GET /health at a customer's address reports that customer's database: UP (200) while it answers, DOWN (503)
 * when it doesn't, without affecting the other customer; no pool counts or names at a customer's address; a result is
 * reused within the cache time.
 */
class CustomerHealthIntegrationTests extends MultiCustomerIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerDirectory directory;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void eachCustomersHealth_isItsOwnDatabase_andAnUnreachableOneDoesNotAffectTheOther() throws Exception {
        for (var host : new String[]{"alpha.pos.localhost", "bravo.pos.localhost"}) {
            var response = mvc.perform(get("/health").header("Host", host)).andReturn().getResponse();
            assertThat(response.getStatus()).as(host).isEqualTo(200);
            assertThat(response.getContentAsString()).contains("\"status\":\"UP\"").doesNotContain("customerPools")
                    .doesNotContain("alpha").doesNotContain("bravo");
        }

        var platform = MultiCustomerFixture.get().platform();
        var port = platform.queryForObject("SELECT d.server_port FROM tenant_databases d JOIN tenants t ON t.id = d.tenant_id "
                + "WHERE t.slug = 'bravo'", Integer.class);
        platform.update("UPDATE d SET server_port = 1 FROM tenant_databases d JOIN tenants t ON t.id = d.tenant_id WHERE t.slug = 'bravo'");
        directory.refresh();
        try {
            // The cached answer (up to 5 s) gives way to the real one.
            var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            int bravo;
            do {
                bravo = mvc.perform(get("/health").header("Host", "bravo.pos.localhost")).andReturn().getResponse().getStatus();
            } while (bravo != 503 && System.nanoTime() < deadline);
            assertThat(bravo).as("bravo's database is unreachable").isEqualTo(503);
            var down = mvc.perform(get("/health").header("Host", "bravo.pos.localhost")).andReturn().getResponse();
            assertThat(down.getContentAsString()).contains("\"status\":\"DOWN\"").doesNotContain("\"code\"");
            assertThat(mvc.perform(get("/health").header("Host", "alpha.pos.localhost")).andReturn().getResponse().getStatus())
                    .as("alpha unaffected").isEqualTo(200);
        } finally {
            platform.update("UPDATE d SET server_port = ? FROM tenant_databases d JOIN tenants t ON t.id = d.tenant_id WHERE t.slug = 'bravo'", port);
            directory.refresh();
        }
    }

    @Test
    void aResult_isReusedWithinTheCacheTime() {
        var alpha = MultiCustomerFixture.get().customer("alpha");
        var check = new CustomerHealthCheck(jdbc, Duration.ofSeconds(2), Duration.ofHours(1));
        try {
            assertThat(check.databaseUp(alpha)).isTrue();
            assertThat(check.databaseUp(alpha)).isTrue();
            assertThat(check.databaseUp(alpha)).isTrue();
            assertThat(check.probes()).as("one probe, then the cached answer").isEqualTo(1);
            assertThat(check.databaseUp(MultiCustomerFixture.get().customer("bravo"))).isTrue();
            assertThat(check.probes()).as("each customer has its own").isEqualTo(2);
        } finally {
            check.shutdown();
        }
    }
}
