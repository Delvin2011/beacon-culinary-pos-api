package com.beaconculinary.api.inventory;

import com.beaconculinary.api.support.AuthTestHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 5.2.2: GRV restructured from one-row-per-ingredient to header + lines, matching a real
 * delivery (one invoice number, one supplier, several items), plus purchase-order linkage and
 * receipt variance. */
@SpringBootTest
@AutoConfigureMockMvc
class GrvHeaderLinesIntegrationTests {
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

    private String postGrv(String body) throws Exception {
        return mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void create_multiLineGrv_writesOneHeaderAndCorrectLinesAndMovements() throws Exception {
        var riceId = createIngredient("Multi Rice");
        var chickenId = createIngredient("Multi Chicken");
        var oilId = createIngredient("Multi Oil");

        var body = "{\"invoiceNumber\":\"INV-100\",\"supplierName\":\"Acme Foods\",\"note\":\"Weekly delivery\",\"lines\":["
                + "{\"ingredientId\":" + riceId + ",\"quantityReceived\":10,\"costPerUnit\":5.00},"
                + "{\"ingredientId\":" + chickenId + ",\"quantityReceived\":8,\"costPerUnit\":42.00},"
                + "{\"ingredientId\":" + oilId + ",\"quantityReceived\":4,\"costPerUnit\":30.00}]}";
        var response = postGrv(body);
        var node = MAPPER.readTree(response);

        var id = node.get("id").asLong();
        org.assertj.core.api.Assertions.assertThat(node.get("invoiceNumber").asText()).isEqualTo("INV-100");
        org.assertj.core.api.Assertions.assertThat(node.get("lines")).hasSize(3);

        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalStock").value(10.0));
        mockMvc.perform(get("/admin/ingredients/" + chickenId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalStock").value(8.0));
        mockMvc.perform(get("/admin/ingredients/" + oilId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalStock").value(4.0));

        // Full detail, fetched back by id, includes every line.
        mockMvc.perform(get("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNumber").value("INV-100"))
                .andExpect(jsonPath("$.note").value("Weekly delivery"))
                .andExpect(jsonPath("$.lines.length()").value(3))
                .andExpect(jsonPath("$.lines[?(@.ingredientId == " + chickenId + ")].quantityReceived").value(8.0));
    }

    @Test
    void create_lineLinkedToPurchaseOrderLine_computesQuantityOrderedAndReceiptVarianceBothDirections() throws Exception {
        var shortId = createIngredient("Variance Short Rice");
        var overId = createIngredient("Variance Over Rice");
        var shortPo = createPurchaseOrder(shortId, 10.0);
        var overPo = createPurchaseOrder(overId, 10.0);

        var body = "{\"invoiceNumber\":\"INV-200\",\"purchaseOrderId\":" + shortPo[0] + ",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + shortId + ",\"purchaseOrderLineId\":" + shortPo[1] + ",\"quantityReceived\":7,\"costPerUnit\":5.00}]}";
        var response = postGrv(body);
        var line = MAPPER.readTree(response).get("lines").get(0);
        org.assertj.core.api.Assertions.assertThat(line.get("quantityOrdered").asDouble()).isEqualTo(10.0);
        org.assertj.core.api.Assertions.assertThat(line.get("receiptVariance").asDouble()).isEqualTo(-3.0);

        var overBody = "{\"invoiceNumber\":\"INV-201\",\"purchaseOrderId\":" + overPo[0] + ",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + overId + ",\"purchaseOrderLineId\":" + overPo[1] + ",\"quantityReceived\":13,\"costPerUnit\":5.00}]}";
        var overResponse = postGrv(overBody);
        var overLine = MAPPER.readTree(overResponse).get("lines").get(0);
        org.assertj.core.api.Assertions.assertThat(overLine.get("quantityOrdered").asDouble()).isEqualTo(10.0);
        org.assertj.core.api.Assertions.assertThat(overLine.get("receiptVariance").asDouble()).isEqualTo(3.0);
    }

    @Test
    void create_lineWithoutPurchaseOrderLink_leavesQuantityOrderedAndVarianceNull() throws Exception {
        var ingredientId = createIngredient("Ad Hoc Rice");

        var body = "{\"invoiceNumber\":\"INV-300\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":5,\"costPerUnit\":5.00}]}";
        var response = postGrv(body);
        var line = MAPPER.readTree(response).get("lines").get(0);

        org.assertj.core.api.Assertions.assertThat(line.get("quantityOrdered").isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(line.get("receiptVariance").isNull()).isTrue();
    }

    @Test
    void create_mismatchedIngredientForLinkedPurchaseOrderLine_returns400() throws Exception {
        var poIngredientId = createIngredient("PO Rice");
        var submittedIngredientId = createIngredient("Different Rice");
        var po = createPurchaseOrder(poIngredientId, 10.0);

        var body = "{\"invoiceNumber\":\"INV-400\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + submittedIngredientId + ",\"purchaseOrderLineId\":" + po[1]
                + ",\"quantityReceived\":5,\"costPerUnit\":5.00}]}";
        mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_multipleGrvsAgainstSamePurchaseOrderLine_bothRecordedWithoutCapping() throws Exception {
        var ingredientId = createIngredient("Partial Delivery Rice");
        var po = createPurchaseOrder(ingredientId, 10.0);

        var firstBody = "{\"invoiceNumber\":\"INV-500\",\"purchaseOrderId\":" + po[0] + ",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + ingredientId + ",\"purchaseOrderLineId\":" + po[1] + ",\"quantityReceived\":6,\"costPerUnit\":5.00}]}";
        postGrv(firstBody);

        var secondBody = "{\"invoiceNumber\":\"INV-501\",\"purchaseOrderId\":" + po[0] + ",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + ingredientId + ",\"purchaseOrderLineId\":" + po[1] + ",\"quantityReceived\":6,\"costPerUnit\":5.00}]}";
        var secondResponse = postGrv(secondBody);
        var secondLine = MAPPER.readTree(secondResponse).get("lines").get(0);

        // No capping: both deliveries recorded in full, each independently computing variance
        // against the PO line's ordered quantity — 6 + 6 = 12 received against 10 ordered.
        org.assertj.core.api.Assertions.assertThat(secondLine.get("quantityOrdered").asDouble()).isEqualTo(10.0);
        org.assertj.core.api.Assertions.assertThat(secondLine.get("receiptVariance").asDouble()).isEqualTo(-4.0);

        mockMvc.perform(get("/admin/ingredients/" + ingredientId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalStock").value(12.0));

        // Purchase order status is unaffected by GRV activity.
        mockMvc.perform(get("/admin/purchase-orders").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$[?(@.id == " + po[0] + ")].status").value("DRAFT"));
    }

    @Test
    void list_filtersByPurchaseOrderId() throws Exception {
        var linkedIngredientId = createIngredient("Linked Rice");
        var adHocIngredientId = createIngredient("Ad Hoc Rice For List");
        var po = createPurchaseOrder(linkedIngredientId, 10.0);

        postGrv("{\"invoiceNumber\":\"INV-600\",\"purchaseOrderId\":" + po[0] + ",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + linkedIngredientId + ",\"purchaseOrderLineId\":" + po[1] + ",\"quantityReceived\":5,\"costPerUnit\":5.00}]}");
        postGrv("{\"invoiceNumber\":\"INV-601\",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + adHocIngredientId + ",\"quantityReceived\":5,\"costPerUnit\":5.00}]}");

        mockMvc.perform(get("/admin/grv").param("purchaseOrderId", String.valueOf(po[0]))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].invoiceNumber").value("INV-600"));
    }

    @Test
    void create_missingInvoiceNumber_returns400() throws Exception {
        var ingredientId = createIngredient("No Invoice Rice");
        var body = "{\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":5,\"costPerUnit\":5.00}]}";
        mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_withNoLines_returns400() throws Exception {
        var body = "{\"invoiceNumber\":\"INV-700\",\"supplierName\":\"Acme Foods\",\"lines\":[]}";
        mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void itemCode_setOnCreateAndUpdate_appearsInIngredientList() throws Exception {
        var createBody = "{\"name\":\"Item Code Rice\",\"unit\":\"KG\",\"countSheetCategory\":\"DRYSTOCK\",\"itemCode\":\"RICE-001\"}";
        var response = mockMvc.perform(post("/admin/ingredients").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCode").value("RICE-001"))
                .andReturn().getResponse().getContentAsString();
        var id = MAPPER.readTree(response).get("id").asLong();

        mockMvc.perform(get("/admin/ingredients").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].itemCode").value("RICE-001"));

        var updateBody = "{\"name\":\"Item Code Rice\",\"unit\":\"KG\",\"countSheetCategory\":\"DRYSTOCK\",\"active\":true,\"itemCode\":\"RICE-002\"}";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/admin/ingredients/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCode").value("RICE-002"));
    }
}
