package com.beaconculinary.api.users;

import com.beaconculinary.api.support.AuthTestHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises GET /users/cashiers — the unauthenticated tile-grid endpoint for the POS
 * login screen. Seed data (V13__seed_menu_and_users.sql, V14__add_pin_auth_to_users.sql)
 * gives us: id 2 = Cashier (CASHIER), id 3 = Admin (ADMIN), id 4 = Cashier B (CASHIER),
 * plus a pre-existing legacy id 1 = "tkay" (USER role) that must NOT appear.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserCashiersIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getCashiers_withoutAuth_succeeds() throws Exception {
        mockMvc.perform(get("/users/cashiers"))
                .andExpect(status().isOk());
    }

    @Test
    void getCashiers_returnsOnlyActiveCashiersAndAdmins_withIdAndNameOnly() throws Exception {
        var response = mockMvc.perform(get("/users/cashiers"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var root = MAPPER.readTree(response);
        assertThat(root.isArray()).isTrue();

        var ids = new HashSet<Long>();
        for (var node : root) {
            var fieldNames = new HashSet<String>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            assertThat(fieldNames).containsExactlyInAnyOrder("id", "name");
            ids.add(node.get("id").asLong());
        }

        assertThat(ids).contains(AuthTestHelper.CASHIER_A_ID, AuthTestHelper.ADMIN_ID, AuthTestHelper.CASHIER_B_ID);
        assertThat(ids).doesNotContain(1L); // legacy "tkay" USER-role account
    }
}
