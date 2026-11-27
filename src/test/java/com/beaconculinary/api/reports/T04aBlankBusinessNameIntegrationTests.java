package com.beaconculinary.api.reports;

import com.beaconculinary.api.support.AuthTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0-4a (contract section 6): with no name in the receipt settings (and, T1-7, no customer directory:
 * single mode), statement reports print no business name: meta.business and the "Business" header
 * item are left out. A name saved in the receipt settings is printed as before.
 */
@SpringBootTest
@AutoConfigureMockMvc
class T04aBlankBusinessNameIntegrationTests {
    private static final String DAY = "2026-01-05";
    private static final String HEADER_BUSINESS = "$.sections[?(@.key == 'header')].items[?(@.label == 'Business')]";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        setBusinessName(null);
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        setBusinessName(null);
    }

    @Test
    void withNoNameAnywhere_noBusinessNameIsPrinted() throws Exception {
        xReading().andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.business").doesNotExist())
                .andExpect(jsonPath(HEADER_BUSINESS, empty()))
                .andExpect(jsonPath("$.sections[?(@.key == 'header')].items[?(@.label == 'Report')]").isNotEmpty());
    }

    @Test
    void theReceiptSettingsNameIsStillPrinted() throws Exception {
        setBusinessName("Canteen at Riverside Park");
        xReading().andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.business.name").value("Canteen at Riverside Park"));
    }

    private ResultActions xReading() throws Exception {
        return mockMvc.perform(get("/admin/reports/x-reading?tradingDay=" + DAY).header("Authorization", "Bearer " + adminToken));
    }

    private void setBusinessName(String name) {
        jdbc.update("UPDATE [receipt_settings] SET [business_name] = ? WHERE [id] = 1", name);
    }
}
