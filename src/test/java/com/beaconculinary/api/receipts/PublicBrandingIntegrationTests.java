package com.beaconculinary.api.receipts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T1-7, single mode (no customer directory): the receipt settings' name, or "" when none is saved. */
@SpringBootTest
@AutoConfigureMockMvc
class PublicBrandingIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE [receipt_settings] SET [business_name] = NULL WHERE [id] = 1");
    }

    @Test
    void theReceiptSettingsName_orEmpty_withoutSignIn() throws Exception {
        jdbc.update("UPDATE [receipt_settings] SET [business_name] = 'Canteen at Riverside Park' WHERE [id] = 1");
        mockMvc.perform(get("/public/branding"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=300"))
                .andExpect(content().json("{\"name\":\"Canteen at Riverside Park\",\"logoUrl\":null}", true));

        jdbc.update("UPDATE [receipt_settings] SET [business_name] = NULL WHERE [id] = 1");
        mockMvc.perform(get("/public/branding"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"name\":\"\",\"logoUrl\":null}", true));
    }
}
