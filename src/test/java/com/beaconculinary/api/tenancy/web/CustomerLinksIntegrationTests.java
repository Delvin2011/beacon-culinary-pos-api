package com.beaconculinary.api.tenancy.web;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.tenancy.CustomerMvc;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1-7: every link the backend builds is on the customer's own primary host (from the platform database), never a
 * configured base URL. An invite created at alpha links to https://alpha.pos.localhost, one at bravo to bravo's host.
 */
class CustomerLinksIntegrationTests extends MultiCustomerIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "T17 link ";

    @Autowired
    private WebApplicationContext context;

    @AfterEach
    void removeTheInvitedStaff() {
        for (var slug : List.of("alpha", "bravo")) {
            var db = MultiCustomerFixture.get().direct(slug);
            db.update("DELETE FROM user_invites WHERE user_id IN (SELECT id FROM users WHERE name LIKE ?)", PREFIX + "%");
            db.update("DELETE FROM users WHERE name LIKE ?", PREFIX + "%");
        }
    }

    @Test
    void anInvite_linksToTheCustomersOwnPrimaryHost() throws Exception {
        for (var slug : List.of("alpha", "bravo")) {
            CustomerMvc.ensureAdmin(MultiCustomerFixture.get().direct(slug));
            var mvc = CustomerMvc.at(context, slug);
            var admin = AuthTestHelper.loginAsAdmin(mvc);
            var body = Map.of("name", PREFIX + UUID.randomUUID(), "role", "CASHIER", "setup", "INVITE");
            var response = mvc.perform(post("/admin/staff").header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body)))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            var issued = MAPPER.readTree(response).get("issuedInvite");
            assertThat(issued.get("link").asText()).as(slug + "'s invite link")
                    .isEqualTo("https://" + slug + ".pos.localhost/invite#t=" + issued.get("token").asText());
        }
    }
}
