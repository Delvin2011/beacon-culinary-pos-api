package com.beaconculinary.api.tenancy.web;

import com.beaconculinary.api.support.tenancy.CustomerMvc;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1-7: GET /public/branding answers, without sign-in, for the customer at the request's address: alpha with the name
 * in its own receipt settings, bravo (none saved) with its name in the customer directory. Suspended, unavailable and
 * unknown addresses get T1-3's answers.
 */
class CustomerBrandingIntegrationTests extends MultiCustomerIntegrationTest {
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private CustomerDirectory directory;

    @AfterEach
    void restore() {
        var fixture = MultiCustomerFixture.get();
        fixture.direct("alpha").update("UPDATE receipt_settings SET business_name = NULL WHERE id = 1");
        fixture.platform().update("UPDATE tenants SET display_name = slug, status = 'ACTIVE' WHERE slug IN ('alpha', 'bravo')");
        directory.refresh();
    }

    @Test
    void eachAddress_getsItsOwnCustomersName_withoutSignIn_cachedForFiveMinutes() throws Exception {
        var fixture = MultiCustomerFixture.get();
        fixture.direct("alpha").update("UPDATE receipt_settings SET business_name = 'Alpha Kitchen' WHERE id = 1");
        fixture.direct("bravo").update("UPDATE receipt_settings SET business_name = NULL WHERE id = 1");
        fixture.platform().update("UPDATE tenants SET display_name = 'Bravo Canteen' WHERE slug = 'bravo'");
        directory.refresh();

        CustomerMvc.at(context, "alpha").perform(get("/public/branding"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=300"))
                .andExpect(content().json("{\"name\":\"Alpha Kitchen\",\"logoUrl\":null}", true));
        CustomerMvc.at(context, "bravo").perform(get("/public/branding"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=300"))
                .andExpect(content().json("{\"name\":\"Bravo Canteen\",\"logoUrl\":null}", true));
    }

    @Test
    void suspendedUnavailableAndUnknownAddresses_getTheT13Answers() throws Exception {
        var platform = MultiCustomerFixture.get().platform();
        platform.update("UPDATE tenants SET status = 'SUSPENDED' WHERE slug = 'alpha'");
        platform.update("UPDATE tenants SET status = 'MIGRATION_FAILED' WHERE slug = 'bravo'");
        directory.refresh();

        CustomerMvc.at(context, "alpha").perform(get("/public/branding"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CUSTOMER_SUSPENDED"));
        CustomerMvc.at(context, "bravo").perform(get("/public/branding"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CUSTOMER_UNAVAILABLE"));
        var unknown = CustomerMvc.at(context, "nobody").perform(get("/public/branding")).andReturn().getResponse();
        assertThat(unknown.getStatus()).isEqualTo(404);
        assertThat(unknown.getContentAsString()).isEmpty();
    }
}
