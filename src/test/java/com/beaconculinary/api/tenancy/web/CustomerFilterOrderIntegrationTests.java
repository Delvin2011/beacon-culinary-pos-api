package com.beaconculinary.api.tenancy.web;

import com.beaconculinary.api.orders.OrderEventBroadcaster;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.tenancy.CustomerMvc;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T1-3: everything that reads a database before the controller does so in the request's own customer, because the
 * customer filter runs first: device resolution (a till cookie from alpha isn't recognised at bravo), login protection
 * (attempts recorded in the customer's own database), sessions (a sign-in at alpha lives in alpha) and the live streams
 * (a stream opened at alpha never receives bravo's events).
 */
class CustomerFilterOrderIntegrationTests extends MultiCustomerIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private OrderEventBroadcaster kitchenBroadcaster;

    private MultiCustomerFixture fixture;
    private MockMvc alpha;
    private MockMvc bravo;

    @BeforeEach
    void setUp() {
        fixture = MultiCustomerFixture.get();
        CustomerMvc.ensureAdmin(fixture.direct("alpha"));
        CustomerMvc.ensureAdmin(fixture.direct("bravo"));
        alpha = CustomerMvc.at(context, "alpha");
        bravo = CustomerMvc.at(context, "bravo");
    }

    @Test
    void aTillRegisteredAtAlpha_isNotRecognisedAtBravo() throws Exception {
        var tills = new O3Tills(alpha, fixture.direct("alpha"), "T13Dev", AuthTestHelper.loginAsAdmin(alpha));
        var till = tills.registerTill();
        try {
            var atAlpha = O3Tills.body(alpha.perform(O3Tills.on(get("/pos/till-status"), till)).andReturn());
            assertThat(atAlpha.get("registered").asBoolean()).isTrue();
            assertThat(atAlpha.get("tillCode").asText()).isEqualTo(till.code());
            var atBravo = O3Tills.body(bravo.perform(O3Tills.on(get("/pos/till-status"), till)).andReturn());
            assertThat(atBravo.get("registered").asBoolean()).as("alpha's cookie means nothing at bravo").isFalse();
        } finally {
            tills.cleanUp();
        }
    }

    @Test
    void signInAttempts_areRecordedInTheCustomersOwnDatabase() throws Exception {
        var email = AuthTestHelper.ADMIN_EMAIL;
        var alphaBefore = attempts("alpha", email);
        var bravoBefore = attempts("bravo", email);
        var wrong = alpha.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("email", email, "password", "not-the-password")))).andReturn();
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(attempts("alpha", email)).isEqualTo(alphaBefore + 1);
        assertThat(attempts("bravo", email)).isEqualTo(bravoBefore);
    }

    @Test
    void aSessionStartedAtAlpha_livesInAlphasDatabase() throws Exception {
        var alphaBefore = sessions("alpha");
        var bravoBefore = sessions("bravo");
        var token = AuthTestHelper.loginAsAdmin(alpha);
        assertThat(sessions("alpha")).isEqualTo(alphaBefore + 1);
        assertThat(sessions("bravo")).isEqualTo(bravoBefore);
        // The token works at alpha, where its session is.
        assertThat(alpha.perform(get("/pos/till-status").header("Authorization", "Bearer " + token)).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aStreamOpenedAtAlpha_neverReceivesBravosEvents() throws Exception {
        var token = AuthTestHelper.loginAsAdmin(alpha);
        var stream = alpha.perform(get("/kitchen/orders/stream").header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        assertThat(stream.getRequest().isAsyncStarted()).isTrue();
        try {
            // What the kitchen service does after an order commits, at each customer.
            CustomerContext.runAs(fixture.customer("bravo"), () -> kitchenBroadcaster.broadcast(Map.of("marker", "bravos-order")));
            CustomerContext.runAs(fixture.customer("alpha"), () -> kitchenBroadcaster.broadcast(Map.of("marker", "alphas-order")));
            var received = stream.getResponse().getContentAsString();
            assertThat(received).contains("alphas-order").doesNotContain("bravos-order");
        } finally {
            stream.getRequest().getAsyncContext().complete();
        }
    }

    private int attempts(String slug, String identifier) {
        return fixture.direct(slug).queryForObject("SELECT COUNT(*) FROM login_attempts WHERE identifier = ?", Integer.class, identifier);
    }

    private int sessions(String slug) {
        return fixture.direct(slug).queryForObject("SELECT COUNT(*) FROM user_sessions", Integer.class);
    }
}
