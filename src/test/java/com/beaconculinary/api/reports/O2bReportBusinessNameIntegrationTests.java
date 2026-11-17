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

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O2b (contract 8.2, item 1): statement reports print the receipt settings' business name, falling
 * back to app.business.name ("Beacon Culinary" in tests) while it's empty.
 */
@SpringBootTest
@AutoConfigureMockMvc
class O2bReportBusinessNameIntegrationTests {
    private static final String DAY = "2026-01-05";
    private static final String HEADER_BUSINESS = "$.sections[?(@.key == 'header')].items[?(@.label == 'Business')].value";

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
    void withNoNameSaved_theServerSettingIsPrinted() throws Exception {
        xReading().andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.business.name").value("Beacon Culinary"))
                .andExpect(jsonPath(HEADER_BUSINESS, contains("Beacon Culinary")));
    }

    @Test
    void withANameSaved_theReceiptSettingsNameIsPrinted() throws Exception {
        setBusinessName("Canteen at Riverside Park");

        xReading().andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.business.name").value("Canteen at Riverside Park"))
                .andExpect(jsonPath(HEADER_BUSINESS, contains("Canteen at Riverside Park")));
    }

    @Test
    void aBlankStoredName_fallsBackToTheServerSetting() throws Exception {
        setBusinessName("   ");

        xReading().andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.business.name").value("Beacon Culinary"));
    }

    private ResultActions xReading() throws Exception {
        return mockMvc.perform(get("/admin/reports/x-reading?tradingDay=" + DAY)
                .header("Authorization", "Bearer " + adminToken));
    }

    private void setBusinessName(String name) {
        jdbc.update("UPDATE [receipt_settings] SET [business_name] = ? WHERE [id] = 1", name);
    }
}
