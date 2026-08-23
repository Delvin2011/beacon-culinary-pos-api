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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 5 Revision: STOCK_CLERK/STOCK_ADMIN role hierarchy and the StockRequest
 * request-then-authorize flow for ISSUE and WASTE. Adapted to this codebase's already-built
 * Location model — an authorized ISSUE always moves Main Store -&gt; Kitchen, the only two
 * locations that exist. */
@SpringBootTest
@AutoConfigureMockMvc
class StockRequestIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private StockRequestRepository stockRequestRepository;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;
    @Autowired
    private GrvRepository grvRepository;
    @Autowired
    private WasteEntryRepository wasteEntryRepository;

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
        stockRequestRepository.deleteAll();
        ingredientStockMovementRepository.deleteAll();
        grvRepository.deleteAll();
        wasteEntryRepository.deleteAll();
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

    private void receiveIntoMainStore(long ingredientId, String quantity) throws Exception {
        var body = "{\"invoiceNumber\":\"INV-" + ingredientId + "\",\"supplierName\":\"Supplier A\","
                + "\"lines\":[{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":" + quantity + ",\"costPerUnit\":1.00}]}";
        mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private long createStockRequest(String token, String requestType, String linesJson) throws Exception {
        return createStockRequest(token, requestType, null, linesJson);
    }

    private long createStockRequest(String token, String requestType, Long locationId, String linesJson) throws Exception {
        var locationField = locationId == null ? "" : "\"locationId\":" + locationId + ",";
        var body = "{\"requestType\":\"" + requestType + "\"," + locationField + "\"lines\":" + linesJson + "}";
        var response = mockMvc.perform(post("/stock-requests").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long mainStoreLocationId() throws Exception {
        var response = mockMvc.perform(get("/locations").header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        for (var node : MAPPER.readTree(response)) {
            if (node.get("name").asText().equalsIgnoreCase("Main Store")) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException("Main Store location not seeded");
    }

    @Test
    void create_asStockClerk_multiLineIssueRequest_visibleToClerkAndStockAdmin() throws Exception {
        var riceId = createIngredient("Request Rice");
        var oilId = createIngredient("Request Oil");

        var id = createStockRequest(stockClerkToken, "ISSUE",
                "[{\"ingredientId\":" + riceId + ",\"quantity\":5},{\"ingredientId\":" + oilId + ",\"quantity\":2}]");

        mockMvc.perform(get("/stock-requests/" + id).header("Authorization", "Bearer " + stockClerkToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestType").value("ISSUE"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.lines.length()").value(2));

        mockMvc.perform(get("/stock-requests").param("status", "REQUESTED").header("Authorization", "Bearer " + stockAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());
    }

    @Test
    void stockClerk_cannotReadAnotherUsersRequest() throws Exception {
        var riceId = createIngredient("Ownership Rice");
        var id = createStockRequest(adminToken, "ISSUE", "[{\"ingredientId\":" + riceId + ",\"quantity\":5}]");

        mockMvc.perform(get("/stock-requests/" + id).header("Authorization", "Bearer " + stockClerkToken))
                .andExpect(status().isForbidden());

        // STOCK_ADMIN/ADMIN see everything, no ownership restriction.
        mockMvc.perform(get("/stock-requests/" + id).header("Authorization", "Bearer " + stockAdminToken))
                .andExpect(status().isOk());
    }

    @Test
    void stockClerk_getsForbidden_onActionEndpoint() throws Exception {
        var riceId = createIngredient("Forbidden Action Rice");
        var id = createStockRequest(stockClerkToken, "ISSUE", "[{\"ingredientId\":" + riceId + ",\"quantity\":5}]");

        mockMvc.perform(post("/stock-requests/" + id + "/action").header("Authorization", "Bearer " + stockClerkToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":" + riceId + ",\"actionedQuantity\":5}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void action_issueRequest_fullApproval_movesStockMainStoreToKitchen() throws Exception {
        var riceId = createIngredient("Issue Rice");
        receiveIntoMainStore(riceId, "10.0000");
        var id = createStockRequest(stockClerkToken, "ISSUE", "[{\"ingredientId\":" + riceId + ",\"quantity\":6}]");

        mockMvc.perform(post("/stock-requests/" + id + "/action").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":" + riceId + ",\"actionedQuantity\":6}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIONED"))
                .andExpect(jsonPath("$.lines[0].actionedQuantity").value(6.0));

        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalStock").value(10.0))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Main Store')].stock").value(4.0))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Kitchen')].stock").value(6.0));
    }

    @Test
    void action_issueRequest_exceedingAvailableMainStoreStock_returns400_writesNothing() throws Exception {
        var riceId = createIngredient("Over Issue Rice");
        receiveIntoMainStore(riceId, "5.0000");
        var id = createStockRequest(stockClerkToken, "ISSUE", "[{\"ingredientId\":" + riceId + ",\"quantity\":10}]");

        mockMvc.perform(post("/stock-requests/" + id + "/action").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":" + riceId + ",\"actionedQuantity\":10}]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalStock").value(5.0))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Kitchen')].stock").value(0));
    }

    @Test
    void action_wasteRequest_partialApproval_writesApprovedAmountOnly() throws Exception {
        var riceId = createIngredient("Waste Request Rice");
        receiveIntoMainStore(riceId, "20.0000");
        var id = createStockRequest(stockClerkToken, "WASTE", mainStoreLocationId(),
                "[{\"ingredientId\":" + riceId + ",\"quantity\":10,\"reason\":\"Spoiled\"}]");

        mockMvc.perform(post("/stock-requests/" + id + "/action").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":" + riceId + ",\"actionedQuantity\":6}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_ACTIONED"))
                .andExpect(jsonPath("$.lines[0].actionedQuantity").value(6.0));

        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalStock").value(14.0))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Kitchen')].stock").value(0));
    }

    @Test
    void action_wasteRequest_exceedingRequestedQuantity_returns400() throws Exception {
        var riceId = createIngredient("Over Waste Rice");
        receiveIntoMainStore(riceId, "20.0000");
        var id = createStockRequest(stockClerkToken, "WASTE", mainStoreLocationId(),
                "[{\"ingredientId\":" + riceId + ",\"quantity\":5,\"reason\":\"Spoiled\"}]");

        mockMvc.perform(post("/stock-requests/" + id + "/action").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":" + riceId + ",\"actionedQuantity\":6}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void action_onAlreadyActionedRequest_returns409() throws Exception {
        var riceId = createIngredient("Already Actioned Rice");
        receiveIntoMainStore(riceId, "10.0000");
        var id = createStockRequest(stockClerkToken, "ISSUE", "[{\"ingredientId\":" + riceId + ",\"quantity\":5}]");

        var fullAction = "{\"lines\":[{\"ingredientId\":" + riceId + ",\"actionedQuantity\":5}]}";
        mockMvc.perform(post("/stock-requests/" + id + "/action").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(fullAction))
                .andExpect(status().isOk());

        mockMvc.perform(post("/stock-requests/" + id + "/action").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(fullAction))
                .andExpect(status().isConflict());
    }

    @Test
    void directWaste_stillWorksForStockAdmin_withDirectWasteSourceType() throws Exception {
        var riceId = createIngredient("Direct Waste Rice");
        receiveIntoMainStore(riceId, "10.0000");

        mockMvc.perform(post("/admin/waste").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ingredientId\":" + riceId + ",\"locationId\":" + mainStoreLocationId()
                                + ",\"quantity\":3,\"reason\":\"Spoiled\"}"))
                .andExpect(status().isCreated());

        var directWasteMovementExists = ingredientStockMovementRepository.findAll().stream()
                .anyMatch(m -> riceId == m.getIngredient().getId() && m.getSourceType() == MovementSourceType.DIRECT_WASTE);
        assertThat(directWasteMovementExists).isTrue();
    }

    @Test
    void stockClerk_isForbidden_fromGrvStockTakePurchaseOrdersIngredients() throws Exception {
        mockMvc.perform(get("/admin/grv").header("Authorization", "Bearer " + stockClerkToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/stock-takes").header("Authorization", "Bearer " + stockClerkToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/purchase-orders").header("Authorization", "Bearer " + stockClerkToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/ingredients").header("Authorization", "Bearer " + stockClerkToken)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/waste").header("Authorization", "Bearer " + stockClerkToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void stockAdmin_canAccessGrvStockTakePurchaseOrdersIngredients_butNotRecipeOrDailyPlanning() throws Exception {
        mockMvc.perform(get("/admin/grv").header("Authorization", "Bearer " + stockAdminToken)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/stock-takes").header("Authorization", "Bearer " + stockAdminToken)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/purchase-orders").header("Authorization", "Bearer " + stockAdminToken)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/ingredients").header("Authorization", "Bearer " + stockAdminToken)).andExpect(status().isOk());

        // Catalog/recipe and daily-planning stay ADMIN-only — STOCK_ADMIN is not a superset there.
        mockMvc.perform(get("/admin/components/1/recipe").header("Authorization", "Bearer " + stockAdminToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/daily-planning/2026-01-15/ingredient-requirements").param("period", "Lunch")
                        .header("Authorization", "Bearer " + stockAdminToken))
                .andExpect(status().isForbidden());
    }
}
