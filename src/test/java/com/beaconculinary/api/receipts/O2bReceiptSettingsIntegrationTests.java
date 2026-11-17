package com.beaconculinary.api.receipts;

import com.beaconculinary.api.support.AuthTestHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O2b step 3: GET /pos/receipt-settings, GET and PUT /admin/receipt-settings (contract section 2).
 * Each test starts and ends with the row back to its empty, never-saved state.
 */
@SpringBootTest
@AutoConfigureMockMvc
class O2bReceiptSettingsIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String cashierToken;

    @BeforeEach
    void setUp() throws Exception {
        resetRow();
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
    }

    @AfterEach
    void tearDown() {
        resetRow();
    }

    // ---- reading before anything is saved ----

    @Test
    void pos_beforeAnySave_returnsTheEmptyDefaults() throws Exception {
        var body = body(getAs("/pos/receipt-settings", cashierToken).andExpect(status().isOk()));

        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "receiptTitle", "businessName", "addressLines", "phone", "vatNumber", "footerText", "showVatLine");
        assertThat(body.get("receiptTitle").isNull()).isTrue();
        assertThat(body.get("businessName").isNull()).isTrue();
        assertThat(body.get("addressLines").isArray()).isTrue();
        assertThat(body.get("addressLines")).isEmpty();
        assertThat(body.get("phone").isNull()).isTrue();
        assertThat(body.get("vatNumber").isNull()).isTrue();
        assertThat(body.get("footerText").isNull()).isTrue();
        assertThat(body.get("showVatLine").asBoolean()).isFalse();
    }

    @Test
    void admin_beforeAnySave_hasNoUpdatedAtOrBy() throws Exception {
        var body = body(getAs("/admin/receipt-settings", adminToken).andExpect(status().isOk()));

        assertThat(fieldNames(body)).containsExactlyInAnyOrder("receiptTitle", "businessName", "addressLines", "phone",
                "vatNumber", "footerText", "showVatLine", "updatedAt", "updatedBy");
        assertThat(body.get("updatedAt").isNull()).isTrue();
        assertThat(body.get("updatedBy").isNull()).isTrue();
        assertThat(body.get("showVatLine").asBoolean()).isFalse();
    }

    // ---- saving ----

    @Test
    void put_savesTrimmedValues_andRecordsWhoSaved() throws Exception {
        var request = full(null, "  Canteen at Riverside Park  ", List.of(" 12 Main Road ", "   ", "Riverside", "", "Cape Town 8001"),
                " 021 555 0100 ", " 4123456789 ", "Thank you!\nKeep this slip for refunds.", true);

        var saved = body(putAs(adminToken, request).andExpect(status().isOk()));

        assertThat(saved.get("businessName").asText()).isEqualTo("Canteen at Riverside Park");
        assertThat(texts(saved.get("addressLines"))).containsExactly("12 Main Road", "Riverside", "Cape Town 8001");
        assertThat(saved.get("phone").asText()).isEqualTo("021 555 0100");
        assertThat(saved.get("vatNumber").asText()).isEqualTo("4123456789");
        assertThat(saved.get("footerText").asText()).isEqualTo("Thank you!\nKeep this slip for refunds.");
        assertThat(saved.get("showVatLine").asBoolean()).isTrue();
        assertThat(saved.get("updatedBy").asText()).isEqualTo("Canteen Admin");
        assertThat(saved.get("updatedAt").asText()).endsWith("Z");

        var updatedById = jdbc.queryForObject("SELECT [updated_by] FROM [receipt_settings] WHERE [id] = 1", Long.class);
        assertThat(updatedById).isEqualTo(AuthTestHelper.adminId(mockMvc));

        var pos = body(getAs("/pos/receipt-settings", cashierToken).andExpect(status().isOk()));
        assertThat(pos.get("businessName").asText()).isEqualTo("Canteen at Riverside Park");
        assertThat(texts(pos.get("addressLines"))).containsExactly("12 Main Road", "Riverside", "Cape Town 8001");
        assertThat(pos.get("showVatLine").asBoolean()).isTrue();
        assertThat(pos.has("updatedAt")).isFalse();
        assertThat(pos.has("updatedBy")).isFalse();

        var admin = body(getAs("/admin/receipt-settings", adminToken).andExpect(status().isOk()));
        assertThat(admin).isEqualTo(saved);
    }

    @Test
    void put_replacesEverything_blankAndMissingValuesBecomeNull() throws Exception {
        putAs(adminToken, full(null, "Canteen", List.of("1 Road", "Town"), "021", "4123", "Thanks", true))
                .andExpect(status().isOk());

        var request = new HashMap<String, Object>();
        request.put("businessName", "   ");
        request.put("addressLines", List.of());
        request.put("phone", "");
        request.put("showVatLine", false);
        var cleared = body(putAs(adminToken, request).andExpect(status().isOk()));

        assertThat(cleared.get("businessName").isNull()).isTrue();
        assertThat(cleared.get("addressLines")).isEmpty();
        assertThat(cleared.get("phone").isNull()).isTrue();
        assertThat(cleared.get("vatNumber").isNull()).isTrue();
        assertThat(cleared.get("footerText").isNull()).isTrue();
        assertThat(cleared.get("showVatLine").asBoolean()).isFalse();
        assertThat(cleared.get("updatedBy").asText()).isEqualTo("Canteen Admin");

        var row = jdbc.queryForMap("SELECT * FROM [receipt_settings] WHERE [id] = 1");
        assertThat(row.get("business_name")).isNull();
        assertThat(row.get("address_line_1")).isNull();
        assertThat(row.get("address_line_2")).isNull();
        assertThat(row.get("phone")).isNull();
    }

    @Test
    void put_nullAddressLines_areTreatedAsNone() throws Exception {
        var request = new HashMap<String, Object>();
        request.put("businessName", "Canteen");
        request.put("addressLines", null);
        request.put("showVatLine", false);

        var saved = body(putAs(adminToken, request).andExpect(status().isOk()));

        assertThat(saved.get("addressLines").isArray()).isTrue();
        assertThat(saved.get("addressLines")).isEmpty();
    }

    @Test
    void put_acceptsValuesAtTheirLimits() throws Exception {
        var footer = "x".repeat(250) + "\n" + "y".repeat(249);
        var request = full(null, "b".repeat(100), List.of("a".repeat(100), "2", "3", "4"), "p".repeat(50), "v".repeat(20), footer, true);

        var saved = body(putAs(adminToken, request).andExpect(status().isOk()));

        assertThat(saved.get("businessName").asText()).hasSize(100);
        assertThat(saved.get("addressLines")).hasSize(4);
        assertThat(saved.get("phone").asText()).hasSize(50);
        assertThat(saved.get("vatNumber").asText()).hasSize(20);
        assertThat(saved.get("footerText").asText()).isEqualTo(footer);
    }

    @Test
    void put_fiveLinesWithOneBlank_keepsFour() throws Exception {
        var saved = body(putAs(adminToken, full(null, null, List.of("1", " ", "2", "3", "4"), null, null, null, false))
                .andExpect(status().isOk()));

        assertThat(texts(saved.get("addressLines"))).containsExactly("1", "2", "3", "4");
    }

    @Test
    void put_savesTheReceiptTitle_trimmed_andShowsItOnBothReads() throws Exception {
        var saved = body(putAs(adminToken, full(" Tax Invoice ", "Canteen", List.of(), null, null, null, true))
                .andExpect(status().isOk()));

        assertThat(saved.get("receiptTitle").asText()).isEqualTo("Tax Invoice");
        assertThat(body(getAs("/admin/receipt-settings", adminToken)).get("receiptTitle").asText()).isEqualTo("Tax Invoice");
        assertThat(body(getAs("/pos/receipt-settings", cashierToken)).get("receiptTitle").asText()).isEqualTo("Tax Invoice");
        assertThat(jdbc.queryForObject("SELECT [receipt_title] FROM [receipt_settings] WHERE [id] = 1", String.class))
                .isEqualTo("Tax Invoice");
    }

    // ---- validation ----

    @Test
    void put_receiptTitleTooLong_returns400() throws Exception {
        var errors = body(putAs(adminToken, full("t".repeat(51), "Canteen", List.of(), null, null, null, true))
                .andExpect(status().isBadRequest()));

        assertThat(fieldNames(errors)).containsExactly("receiptTitle");
        assertThat(errors.get("receiptTitle").asText()).isEqualTo("Receipt title must be 50 characters or fewer.");
        assertNothingSaved();
    }


    @Test
    void put_valuesTooLong_returnsAFieldMap_andSavesNothing() throws Exception {
        var request = full("t".repeat(51), "b".repeat(101), List.of("a".repeat(101)), "p".repeat(51), "v".repeat(21), "f".repeat(501), true);

        var errors = body(putAs(adminToken, request).andExpect(status().isBadRequest()));

        assertThat(errors.get("receiptTitle").asText()).isEqualTo("Receipt title must be 50 characters or fewer.");
        assertThat(errors.get("businessName").asText()).isEqualTo("Business name must be 100 characters or fewer.");
        assertThat(errors.get("addressLines").asText()).isEqualTo("Each address line must be 100 characters or fewer.");
        assertThat(errors.get("phone").asText()).isEqualTo("Phone must be 50 characters or fewer.");
        assertThat(errors.get("vatNumber").asText()).isEqualTo("VAT number must be 20 characters or fewer.");
        assertThat(errors.get("footerText").asText()).isEqualTo("Footer text must be 500 characters or fewer.");
        assertNothingSaved();
    }

    @Test
    void put_lengthIsCheckedAfterTrimming() throws Exception {
        var saved = body(putAs(adminToken, full(null, "  " + "b".repeat(100) + "  ", List.of(), null, " " + "v".repeat(20) + " ", null, false))
                .andExpect(status().isOk()));

        assertThat(saved.get("businessName").asText()).hasSize(100);
        assertThat(saved.get("vatNumber").asText()).hasSize(20);
    }

    @Test
    void put_moreThanFourAddressLines_returns400() throws Exception {
        var errors = body(putAs(adminToken, full(null, "Canteen", List.of("1", "2", "3", "4", "5"), null, null, null, false))
                .andExpect(status().isBadRequest()));

        assertThat(errors.get("addressLines").asText()).isEqualTo("At most 4 address lines.");
        assertNothingSaved();
    }

    @Test
    void put_withoutShowVatLine_returns400() throws Exception {
        var request = new HashMap<String, Object>();
        request.put("businessName", "Canteen");

        var errors = body(putAs(adminToken, request).andExpect(status().isBadRequest()));

        assertThat(fieldNames(errors)).containsExactly("showVatLine");
        assertThat(errors.get("showVatLine").asText()).isEqualTo("showVatLine is required.");
        assertNothingSaved();
    }

    // ---- who may call ----

    @Test
    void writesAndAdminReads_areAdminOnly() throws Exception {
        var request = full(null, "Canteen", List.of(), null, null, null, true);
        for (var token : List.of(cashierToken, AuthTestHelper.loginAsHeadChef(mockMvc),
                AuthTestHelper.loginAsStockAdmin(mockMvc), AuthTestHelper.loginAsKitchen(mockMvc))) {
            putAs(token, request).andExpect(status().isForbidden());
            getAs("/admin/receipt-settings", token).andExpect(status().isForbidden());
        }
        assertNothingSaved();
    }

    @Test
    void posRead_isForCashierAndAdmin() throws Exception {
        getAs("/pos/receipt-settings", adminToken).andExpect(status().isOk());
        getAs("/pos/receipt-settings", cashierToken).andExpect(status().isOk());
        getAs("/pos/receipt-settings", AuthTestHelper.loginAsKitchen(mockMvc)).andExpect(status().isForbidden());
        getAs("/pos/receipt-settings", AuthTestHelper.loginAsBarista(mockMvc)).andExpect(status().isForbidden());
    }

    @Test
    void withoutSignIn_returns401() throws Exception {
        mockMvc.perform(get("/pos/receipt-settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/receipt-settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/receipt-settings").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(full(null, "Canteen", List.of(), null, null, null, true))))
                .andExpect(status().isUnauthorized());
        assertNothingSaved();
    }

    // ---- helpers ----

    private void resetRow() {
        jdbc.update("""
                UPDATE [receipt_settings]
                SET [receipt_title] = NULL, [business_name] = NULL, [address_line_1] = NULL, [address_line_2] = NULL, [address_line_3] = NULL,
                    [address_line_4] = NULL, [phone] = NULL, [vat_number] = NULL, [footer_text] = NULL,
                    [show_vat_line] = 0, [updated_at] = NULL, [updated_by] = NULL, [updated_by_name] = NULL
                WHERE [id] = 1""");
    }

    private void assertNothingSaved() {
        var row = jdbc.queryForMap("SELECT [business_name], [updated_at], [show_vat_line] FROM [receipt_settings] WHERE [id] = 1");
        assertThat(row.get("business_name")).isNull();
        assertThat(row.get("updated_at")).isNull();
        assertThat(row.get("show_vat_line")).isEqualTo(false);
    }

    private static Map<String, Object> full(String receiptTitle, String businessName, List<String> addressLines, String phone, String vatNumber,
                                            String footerText, Boolean showVatLine) {
        var request = new HashMap<String, Object>();
        request.put("receiptTitle", receiptTitle);
        request.put("businessName", businessName);
        request.put("addressLines", addressLines);
        request.put("phone", phone);
        request.put("vatNumber", vatNumber);
        request.put("footerText", footerText);
        request.put("showVatLine", showVatLine);
        return request;
    }

    private ResultActions getAs(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    private ResultActions putAs(String token, Object request) throws Exception {
        return mockMvc.perform(put("/admin/receipt-settings").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(request)));
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> texts(JsonNode array) {
        var values = new ArrayList<String>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }
}
