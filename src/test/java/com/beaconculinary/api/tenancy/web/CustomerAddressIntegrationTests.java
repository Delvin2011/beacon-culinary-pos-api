package com.beaconculinary.api.tenancy.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beaconculinary.api.common.logging.RequestContextFilter;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * T1-3, multi mode end to end (MockMvc through the real filter chain): the contract answers on an API path, /health and
 * /public/* for unknown, archived, suspended, provisioning and migration-failed customers; the request's customer and
 * tenantId (also on the request log line) for an active customer, cleared afterwards, a stream included.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerAddressIntegrationTests extends MultiCustomerIntegrationTest {
    private static final Map<String, String> EXTRA = Map.of(
            "t13suspended", "SUSPENDED", "t13provisioning", "PROVISIONING",
            "t13migrationfailed", "MIGRATION_FAILED", "t13archived", "ARCHIVED");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerDirectory directory;

    @BeforeAll
    void registerCustomersInOtherStates() {
        var platform = MultiCustomerFixture.get().platform();
        EXTRA.forEach((slug, status) -> {
            var id = platform.queryForObject("INSERT INTO tenants (slug, display_name, status) OUTPUT INSERTED.id VALUES (?, ?, ?)",
                    Long.class, slug, "Display " + slug, status);
            platform.update("INSERT INTO tenant_hosts (tenant_id, host, is_primary) VALUES (?, ?, 1)", id, slug + ".pos.localhost");
        });
        directory.refresh();
    }

    @AfterAll
    void removeThem() {
        var platform = MultiCustomerFixture.get().platform();
        for (var slug : EXTRA.keySet()) {
            platform.update("DELETE FROM tenant_hosts WHERE tenant_id IN (SELECT id FROM tenants WHERE slug = ?)", slug);
            platform.update("DELETE FROM tenants WHERE slug = ?", slug);
        }
        directory.refresh();
    }

    @Test
    void theContract_onAnApiPath_health_andPublic() throws Exception {
        for (var path : List.of("/orders", "/health", "/public/board/today")) {
            expect("nobody.pos.localhost", path, 404, null);
            expect("t13archived.pos.localhost", path, 404, null);
            expect("t13suspended.pos.localhost", path, 503, "CUSTOMER_SUSPENDED");
            expect("t13provisioning.pos.localhost", path, 503, "CUSTOMER_UNAVAILABLE");
            expect("t13migrationfailed.pos.localhost", path, 503, "CUSTOMER_UNAVAILABLE");
        }
    }

    @Test
    void anActiveCustomersRequest_carriesItsSlug_andNothingIsLeftOnTheThread() throws Exception {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var logger = (Logger) LoggerFactory.getLogger(RequestContextFilter.class);
        logger.addAppender(appender);
        try {
            var response = mvc.perform(get("/public/board/today").header("Host", "bravo.pos.localhost")).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            logger.detachAppender(appender);
        }
        var line = appender.list.stream().filter(e -> e.getFormattedMessage().startsWith("request GET /public/board/today"))
                .findFirst().orElseThrow();
        assertThat(line.getMDCPropertyMap()).containsEntry("tenantId", "bravo");
        assertThat(CustomerContext.current()).isEmpty();
        assertThat(MDC.get("tenantId")).isNull();
    }

    @Test
    void aStream_isOpenedAsItsCustomer_andTheThreadIsClearedAfter() throws Exception {
        var result = mvc.perform(get("/public/board/stream").header("Host", "alpha.pos.localhost")).andReturn();
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(CustomerContext.current()).as("cleared once the request thread is done").isEmpty();
        result.getRequest().getAsyncContext().complete();
    }

    private void expect(String host, String path, int status, String code) throws Exception {
        var response = mvc.perform(get(path).header("Host", host)).andReturn().getResponse();
        assertThat(response.getStatus()).as(host + path).isEqualTo(status);
        var body = response.getContentAsString();
        if (code == null) {
            assertThat(body).as(host + path).isEmpty();
        } else {
            assertThat(body).as(host + path).contains("\"code\":\"" + code + "\"");
        }
        var slug = host.substring(0, host.indexOf('.'));
        assertThat(body).as("no customer named").doesNotContain(slug).doesNotContain("Display");
    }
}
