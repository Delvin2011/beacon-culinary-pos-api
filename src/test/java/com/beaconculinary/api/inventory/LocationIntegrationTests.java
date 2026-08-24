package com.beaconculinary.api.inventory;

import com.beaconculinary.api.support.AuthTestHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 5.2.1 Section 7: GET /locations — reference data for dropdowns, any authenticated role,
 * falling through to SecurityConfig's default authenticated() rule rather than needing its own
 * SecurityRules bean. */
@SpringBootTest
@AutoConfigureMockMvc
class LocationIntegrationTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void getAll_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(get("/locations")).andExpect(status().isUnauthorized());
    }

    @Test
    void getAll_returnsSeededLocations_forAnyAuthenticatedRole() throws Exception {
        var cashierToken = AuthTestHelper.loginAsCashier(mockMvc);

        mockMvc.perform(get("/locations").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Main Store')]").exists())
                .andExpect(jsonPath("$[?(@.name == 'Kitchen')]").exists());
    }
}
