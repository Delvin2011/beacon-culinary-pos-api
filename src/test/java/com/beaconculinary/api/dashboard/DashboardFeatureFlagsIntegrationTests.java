package com.beaconculinary.api.dashboard;

import com.beaconculinary.api.support.AuthTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A section whose stage is switched off is left out of the layout (and answers 404); parts of
 * sections that depend on a stage drop out with it. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "dashboard.features.lightMealStock=false",
        "dashboard.features.barStation=false",
        "dashboard.features.complimentary=false",
        "dashboard.features.accountReports=false"})
class DashboardFeatureFlagsIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DashboardCache cache;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cache.clear();
    }

    private ResultActions as(String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + adminToken));
    }

    @Test
    void switchedOffStages_areLeftOut() throws Exception {
        as("/admin/dashboard/layout").andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[*].key", not(hasItem("light-meal-stock"))))
                .andExpect(jsonPath("$.sections[*].key", not(hasItem("account-customers"))))
                .andExpect(jsonPath("$.sections[*].key", hasItem("today")));
        as("/admin/dashboard/sections/light-meal-stock").andExpect(status().isNotFound());
        as("/admin/dashboard/sections/account-customers").andExpect(status().isNotFound());
        as("/admin/dashboard/sections/now").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bar").value(nullValue()))
                .andExpect(jsonPath("$.data.kitchen").exists());
        as("/admin/dashboard/sections/waiting").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.barMedianMinutes").value(nullValue()));
        as("/admin/dashboard/sections/today").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tiles[*].key", not(hasItem("complimentary"))))
                .andExpect(jsonPath("$.data.tiles[*].key", hasItem("discounts")));
    }
}
