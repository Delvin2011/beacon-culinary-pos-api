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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 5.2.3: the ORDER StockRequest type — approving one produces a PurchaseOrder instead of
 * any stock movement, completing the request_type set alongside ISSUE and WASTE. */
@SpringBootTest
@AutoConfigureMockMvc
class OrderStockRequestIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private StockRequestRepository stockRequestRepository;
    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired
    private GrvRepository grvRepository;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;

    private String adminToken;
    private String stockAdminToken;
    private String stockClerkToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        stockAdminToken = AuthTestHelper.loginAsStockAdmin(mockMvc);
        stockClerkToken = AuthTestHelper.loginAsStockClerk(mockMvc);
    }

    @AfterEach
    void tearDown() {
        // grv_lines FKs to purchase_order_lines, and purchase_orders FKs to stock_requests — GRV
        // must go before Purchase Order, which must go before Stock Request.
        ingredientStockMovementRepository.deleteAll();
        grvRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        stockRequestRepository.deleteAll();
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

    private long createOrderRequest(String linesJson) throws Exception {
        var body = "{\"requestType\":\"ORDER\",\"lines\":" + linesJson + "}";
        var response = mockMvc.perform(post("/stock-requests").header("Authorization", "Bearer " + stockClerkToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    @Test
    void create_multiLineOrderRequest_visibleToStockAdminInPendingQueue() throws Exception {
        var riceId = createIngredient("Order Rice");
        var oilId = createIngredient("Order Oil");

        var id = createOrderRequest("[{\"ingredientId\":" + riceId + ",\"quantity\":10,\"reason\":\"Running low\"},"
                + "{\"ingredientId\":" + oilId + ",\"quantity\":5,\"reason\":\"New menu item\"}]");

        mockMvc.perform(get("/stock-requests").param("type", "ORDER").param("status", "REQUESTED")
                        .header("Authorization", "Bearer " + stockAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());

        mockMvc.perform(get("/stock-requests/" + id).header("Authorization", "Bearer " + stockAdminToken))
                .andExpect(jsonPath("$.requestType").value("ORDER"))
                .andExpect(jsonPath("$.lines[?(@.ingredientId == " + riceId + ")].reason").value("Running low"));
    }

    @Test
    void action_withoutSupplierName_returns400() throws Exception {
        var riceId = createIngredient("No Supplier Rice");
        var id = createOrderRequest("[{\"ingredientId\":" + riceId + ",\"quantity\":10}]");

        mockMvc.perform(post("/stock-requests/" + id + "/action").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":" + riceId + ",\"actionedQuantity\":10}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void action_withSupplierName_createsPurchaseOrder_linkedBack_noStockMovement() throws Exception {
        var riceId = createIngredient("Approved Order Rice");
        var oilId = createIngredient("Approved Order Oil");
        var id = createOrderRequest("[{\"ingredientId\":" + riceId + ",\"quantity\":10},"
                + "{\"ingredientId\":" + oilId + ",\"quantity\":5}]");

        var actionBody = "{\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + riceId + ",\"actionedQuantity\":12}," // admin can order more than requested
                + "{\"ingredientId\":" + oilId + ",\"actionedQuantity\":5}]}";
        var response = mockMvc.perform(post("/stock-requests/" + id + "/action")
                        .header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIONED"))
                .andExpect(jsonPath("$.purchaseOrderId").isNumber())
                .andReturn().getResponse().getContentAsString();
        var purchaseOrderId = MAPPER.readTree(response).get("purchaseOrderId").asLong();

        mockMvc.perform(get("/admin/purchase-orders/" + purchaseOrderId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierName").value("Acme Foods"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.stockRequestId").value(id))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[?(@.ingredientId == " + riceId + ")].quantity").value(12.0));

        // Ordering is not receiving — no IngredientStockMovement of any kind was written.
        var hasMovements = ingredientStockMovementRepository.findAll().stream()
                .anyMatch(m -> m.getIngredient().getId() == riceId || m.getIngredient().getId() == oilId);
        org.assertj.core.api.Assertions.assertThat(hasMovements).isFalse();

        // Re-reading the request shows the same link.
        mockMvc.perform(get("/stock-requests/" + id).header("Authorization", "Bearer " + stockAdminToken))
                .andExpect(jsonPath("$.purchaseOrderId").value(purchaseOrderId));
    }

    @Test
    void resultingPurchaseOrder_canReceiveGrvAgainstItsLines_likeAnyOtherPo() throws Exception {
        var riceId = createIngredient("GRV Against Order Rice");
        var id = createOrderRequest("[{\"ingredientId\":" + riceId + ",\"quantity\":10}]");

        var actionBody = "{\"supplierName\":\"Acme Foods\",\"lines\":[{\"ingredientId\":" + riceId + ",\"actionedQuantity\":10}]}";
        var response = mockMvc.perform(post("/stock-requests/" + id + "/action")
                        .header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody))
                .andReturn().getResponse().getContentAsString();
        var purchaseOrderId = MAPPER.readTree(response).get("purchaseOrderId").asLong();

        var poDetail = mockMvc.perform(get("/admin/purchase-orders/" + purchaseOrderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        var poLineId = MAPPER.readTree(poDetail).get("lines").get(0).get("id").asLong();

        var grvBody = "{\"invoiceNumber\":\"INV-ORDER-1\",\"purchaseOrderId\":" + purchaseOrderId + ",\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + riceId + ",\"purchaseOrderLineId\":" + poLineId + ",\"quantityReceived\":10,\"costPerUnit\":5.00}]}";
        mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(grvBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].quantityOrdered").value(10.0))
                .andExpect(jsonPath("$.lines[0].receiptVariance").value(0.0));

        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Main Store')].stock").value(10.0));
    }

    @Test
    void stockClerk_getsForbidden_actioningOwnOrderRequest() throws Exception {
        var riceId = createIngredient("Forbidden Order Rice");
        var id = createOrderRequest("[{\"ingredientId\":" + riceId + ",\"quantity\":10}]");

        mockMvc.perform(post("/stock-requests/" + id + "/action").header("Authorization", "Bearer " + stockClerkToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierName\":\"Acme Foods\",\"lines\":[{\"ingredientId\":" + riceId + ",\"actionedQuantity\":10}]}"))
                .andExpect(status().isForbidden());
    }
}
