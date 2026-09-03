package com.beaconculinary.api.inventory;

import com.beaconculinary.api.support.AuthTestHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 5.2.6 — PATCH /admin/grv/{id}, a same-day correction of a data-entry mistake. Ingredient,
 * purchase-order link, and line identity stay immutable; only header fields and a named line's
 * quantityReceived/costPerUnit can change, and only on the calendar day (business timezone) the
 * GRV was received. */
@SpringBootTest
@AutoConfigureMockMvc
class GrvEditIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private GrvRepository grvRepository;
    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        ingredientStockMovementRepository.deleteAll();
        grvRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        ingredientRepository.deleteAll();
    }

    private long createIngredient(String name) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"unit\":\"KG\",\"countSheetCategory\":\"DRYSTOCK\"}";
        var response = mockMvc.perform(post("/admin/ingredients").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    /** Returns {@code [purchaseOrderId, purchaseOrderLineId]}. */
    private long[] createPurchaseOrder(long ingredientId, double quantity) throws Exception {
        var body = "{\"supplierName\":\"Acme Foods\",\"lines\":[{\"ingredientId\":" + ingredientId
                + ",\"quantity\":" + quantity + "}]}";
        var response = mockMvc.perform(post("/admin/purchase-orders").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var node = MAPPER.readTree(response);
        return new long[]{node.get("id").asLong(), node.get("lines").get(0).get("id").asLong()};
    }

    private com.fasterxml.jackson.databind.JsonNode postGrv(String body) throws Exception {
        var response = mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response);
    }

    @Test
    void update_sameDay_updatesHeaderAndLineFieldsAndReturnsUpdatedGrv() throws Exception {
        var ingredientId = createIngredient("Edit Rice");
        var created = postGrv("{\"invoiceNumber\":\"INV-900\",\"supplierName\":\"Acme Foods\",\"note\":\"Original note\",\"lines\":["
                + "{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":10,\"costPerUnit\":5.00}]}");
        var id = created.get("id").asLong();
        var lineId = created.get("lines").get(0).get("id").asLong();

        var patchBody = "{\"invoiceNumber\":\"INV-900-CORRECTED\",\"supplierName\":\"Acme Foods Ltd\",\"note\":\"Fixed cost\",\"lines\":["
                + "{\"id\":" + lineId + ",\"quantityReceived\":6,\"costPerUnit\":5.50}]}";
        mockMvc.perform(patch("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNumber").value("INV-900-CORRECTED"))
                .andExpect(jsonPath("$.supplierName").value("Acme Foods Ltd"))
                .andExpect(jsonPath("$.note").value("Fixed cost"))
                .andExpect(jsonPath("$.lines[0].quantityReceived").value(6.0))
                .andExpect(jsonPath("$.lines[0].costPerUnit").value(5.50));

        // Persisted, not just echoed back.
        mockMvc.perform(get("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.invoiceNumber").value("INV-900-CORRECTED"))
                .andExpect(jsonPath("$.lines[0].quantityReceived").value(6.0));
    }

    @Test
    void update_changedQuantity_adjustsStockMovementSoTotalStockMatchesCorrectedValue() throws Exception {
        var ingredientId = createIngredient("Edit Stock Rice");
        var created = postGrv("{\"invoiceNumber\":\"INV-901\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":10,\"costPerUnit\":5.00}]}");
        var id = created.get("id").asLong();
        var lineId = created.get("lines").get(0).get("id").asLong();

        mockMvc.perform(get("/admin/ingredients/" + ingredientId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalStock").value(10.0));

        var patchBody = "{\"invoiceNumber\":\"INV-901\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"id\":" + lineId + ",\"quantityReceived\":6,\"costPerUnit\":5.00}]}";
        mockMvc.perform(patch("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isOk());

        // Corrected to 6, never stacked (16) or left stale (10) — the movement was adjusted in
        // place, not appended to.
        mockMvc.perform(get("/admin/ingredients/" + ingredientId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalStock").value(6.0));
    }

    @Test
    void update_purchaseOrderLinkedLine_recomputesReceiptVarianceFromCorrectedQuantity() throws Exception {
        var ingredientId = createIngredient("Edit Variance Rice");
        var po = createPurchaseOrder(ingredientId, 10.0);

        var created = postGrv("{\"invoiceNumber\":\"INV-902\",\"purchaseOrderId\":" + po[0] + ",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + ingredientId + ",\"purchaseOrderLineId\":" + po[1] + ",\"quantityReceived\":7,\"costPerUnit\":5.00}]}");
        var id = created.get("id").asLong();
        var lineId = created.get("lines").get(0).get("id").asLong();
        Assertions.assertThat(created.get("lines").get(0).get("receiptVariance").asDouble()).isEqualTo(-3.0);

        var patchBody = "{\"invoiceNumber\":\"INV-902\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"id\":" + lineId + ",\"quantityReceived\":10,\"costPerUnit\":5.00}]}";
        mockMvc.perform(patch("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantityOrdered").value(10.0))
                .andExpect(jsonPath("$.lines[0].receiptVariance").value(0.0));
    }

    @Test
    void update_lineIdNotBelongingToThisGrv_returns400() throws Exception {
        var ingredientId = createIngredient("Foreign Line Rice");
        var otherIngredientId = createIngredient("Other Grv Rice");
        var created = postGrv("{\"invoiceNumber\":\"INV-903\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":10,\"costPerUnit\":5.00}]}");
        var id = created.get("id").asLong();
        var otherGrv = postGrv("{\"invoiceNumber\":\"INV-904\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + otherIngredientId + ",\"quantityReceived\":5,\"costPerUnit\":5.00}]}");
        var foreignLineId = otherGrv.get("lines").get(0).get("id").asLong();

        var patchBody = "{\"invoiceNumber\":\"INV-903\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"id\":" + foreignLineId + ",\"quantityReceived\":6,\"costPerUnit\":5.00}]}";
        mockMvc.perform(patch("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_omittingAnExistingLine_returns400() throws Exception {
        var riceId = createIngredient("Omit Rice");
        var chickenId = createIngredient("Omit Chicken");
        var created = postGrv("{\"invoiceNumber\":\"INV-905\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + riceId + ",\"quantityReceived\":10,\"costPerUnit\":5.00},"
                + "{\"ingredientId\":" + chickenId + ",\"quantityReceived\":8,\"costPerUnit\":42.00}]}");
        var id = created.get("id").asLong();
        var riceLineId = created.get("lines").get(0).get("id").asLong();

        // Only names one of the two existing lines — must be rejected, not treated as "delete
        // the other line".
        var patchBody = "{\"invoiceNumber\":\"INV-905\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"id\":" + riceLineId + ",\"quantityReceived\":6,\"costPerUnit\":5.00}]}";
        mockMvc.perform(patch("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_nonPositiveQuantityReceived_returns400() throws Exception {
        var ingredientId = createIngredient("Invalid Quantity Rice");
        var created = postGrv("{\"invoiceNumber\":\"INV-906\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":10,\"costPerUnit\":5.00}]}");
        var id = created.get("id").asLong();
        var lineId = created.get("lines").get(0).get("id").asLong();

        var patchBody = "{\"invoiceNumber\":\"INV-906\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"id\":" + lineId + ",\"quantityReceived\":0,\"costPerUnit\":5.00}]}";
        mockMvc.perform(patch("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_unknownGrvId_returns404() throws Exception {
        var patchBody = "{\"invoiceNumber\":\"INV-907\",\"supplierName\":\"Acme Foods\",\"lines\":[{\"id\":1,\"quantityReceived\":6,\"costPerUnit\":5.00}]}";
        mockMvc.perform(patch("/admin/grv/999999999").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_receivedOnAPreviousDay_returns409AndLeavesGrvUnchanged() throws Exception {
        var ingredientId = createIngredient("Stale Grv Rice");
        var created = postGrv("{\"invoiceNumber\":\"INV-908\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":10,\"costPerUnit\":5.00}]}");
        var id = created.get("id").asLong();
        var lineId = created.get("lines").get(0).get("id").asLong();

        // Backdate received_at directly — the two-day margin keeps this robust against the
        // business-timezone conversion regardless of what zone the test runs in.
        jdbcTemplate.update("UPDATE grv SET received_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(2)), id);

        var patchBody = "{\"invoiceNumber\":\"INV-908-SHOULD-NOT-APPLY\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"id\":" + lineId + ",\"quantityReceived\":1,\"costPerUnit\":1.00}]}";
        mockMvc.perform(patch("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "This GRV can no longer be edited — it was received on a previous day."));

        mockMvc.perform(get("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.invoiceNumber").value("INV-908"))
                .andExpect(jsonPath("$.lines[0].quantityReceived").value(10.0));
        mockMvc.perform(get("/admin/ingredients/" + ingredientId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalStock").value(10.0));
    }
}
