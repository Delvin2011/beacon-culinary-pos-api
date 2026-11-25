package com.beaconculinary.api.tenancy.web;

import com.beaconculinary.api.ops.TenantRegisterCommand;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * T1-3: suspending a customer with TenantRegisterCommand makes its address answer 503 CUSTOMER_SUSPENDED (on /health
 * too, which the frontend polls while suspended) within the directory's refresh time; activating it gives 200 again.
 * The other customer answers 200 throughout. The multi-mode test context refreshes the directory every 3 s.
 */
class SuspendAndRecoverIntegrationTests extends MultiCustomerIntegrationTest {
    private static final Duration WITHIN = Duration.ofSeconds(15);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private Environment environment;

    @Test
    void suspendingBravo_is503Suspended_untilItIsActiveAgain_andAlphaIsUnaffected() throws Exception {
        assertThat(command("suspend", "--slug", "bravo")).isEqualTo(TenantRegisterCommand.OK);
        try {
            var suspended = await("bravo.pos.localhost", r -> r.getStatus() == 503 && body(r).contains("CUSTOMER_SUSPENDED"));
            assertThat(suspended.getContentAsString()).contains("\"code\":\"CUSTOMER_SUSPENDED\"").doesNotContain("bravo");
            assertThat(health("bravo.pos.localhost", "/public/board/today").getStatus()).isEqualTo(503);
            assertThat(health("alpha.pos.localhost", "/health").getStatus()).as("alpha unaffected").isEqualTo(200);
        } finally {
            assertThat(command("activate", "--slug", "bravo")).isEqualTo(TenantRegisterCommand.OK);
        }
        var recovered = await("bravo.pos.localhost", r -> r.getStatus() == 200);
        assertThat(recovered.getContentAsString()).contains("\"status\":\"UP\"");
        assertThat(health("alpha.pos.localhost", "/health").getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse await(String host, Predicate<MockHttpServletResponse> done) throws Exception {
        var deadline = System.nanoTime() + WITHIN.toNanos();
        MockHttpServletResponse response;
        do {
            response = health(host, "/health");
            if (done.test(response)) {
                return response;
            }
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        return response;
    }

    private static String body(MockHttpServletResponse response) {
        try {
            return response.getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MockHttpServletResponse health(String host, String path) throws Exception {
        return mvc.perform(get(path).header("Host", host)).andReturn().getResponse();
    }

    private int command(String... args) {
        var out = new ByteArrayOutputStream();
        var command = new TenantRegisterCommand(environment.getRequiredProperty("app.tenancy.platform.url"),
                environment.getRequiredProperty("app.tenancy.platform.username"),
                environment.getRequiredProperty("app.tenancy.platform.password"),
                new PrintStream(out), new PrintStream(out), Clock.systemUTC(), "t1-3 test");
        return command.run(args);
    }
}
