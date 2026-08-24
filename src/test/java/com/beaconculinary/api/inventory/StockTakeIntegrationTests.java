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

/** Stage 5.2.5: the submit/review Stock Take flow — location-selectable, STOCK_CLERK can submit,
 * only STOCK_ADMIN/ADMIN can review, review is a binary approve/reject that never edits the
 * clerk's physical count. Distinct from the deprecated one-step {@code LegacyStockTake} covered
 * in {@code IngredientLedgerIntegrationTests}/{@code WasteAndStockTakeListIntegrationTests}. */
@SpringBootTest
@AutoConfigureMockMvc
class StockTakeIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private StockTakeRepository stockTakeRepository;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;
    @Autowired
    private GrvRepository grvRepository;
    @Autowired
    private StockRequestRepository stockRequestRepository;

    private String adminToken;
    private String stockAdminToken;
    private String stockClerkToken;
    private long mainStoreId;
    private long kitchenId;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        stockAdminToken = AuthTestHelper.loginAsStockAdmin(mockMvc);
        stockClerkToken = AuthTestHelper.loginAsStockClerk(mockMvc);

        var response = mockMvc.perform(get("/locations").header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        for (var node : MAPPER.readTree(response)) {
            if (node.get("name").asText().equalsIgnoreCase("Main Store")) {
                mainStoreId = node.get("id").asLong();
            } else if (node.get("name").asText().equalsIgnoreCase("Kitchen")) {
                kitchenId = node.get("id").asLong();
            }
        }
    }

    @AfterEach
    void tearDown() {
        stockTakeRepository.deleteAll();
        stockRequestRepository.deleteAll();
        ingredientStockMovementRepository.deleteAll();
        grvRepository.deleteAll();
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

    private void receiveIntoMainStore(long ingredientId, String quantity, String costPerUnit) throws Exception {
        var body = "{\"invoiceNumber\":\"INV-" + ingredientId + "-" + quantity + "\",\"supplierName\":\"Supplier A\","
                + "\"lines\":[{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":" + quantity
                + ",\"costPerUnit\":" + costPerUnit + "}]}";
        mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    // Real Main Store -> Kitchen path, exercising the full cross-stage chain rather than seeding
    // the ledger directly.
    private void issueToKitchen(long ingredientId, String quantity) throws Exception {
        var createBody = "{\"requestType\":\"ISSUE\",\"lines\":[{\"ingredientId\":" + ingredientId + ",\"quantity\":" + quantity + "}]}";
        var response = mockMvc.perform(post("/stock-requests").header("Authorization", "Bearer " + stockClerkToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andReturn().getResponse().getContentAsString();
        var id = MAPPER.readTree(response).get("id").asLong();
        mockMvc.perform(post("/stock-requests/" + id + "/action").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":" + ingredientId + ",\"actionedQuantity\":" + quantity + "}]}"))
                .andExpect(status().isOk());
    }

    private long submitStockTake(String token, long locationId, long ingredientId, String actualQuantity) throws Exception {
        var body = "{\"locationId\":" + locationId + ",\"lines\":[{\"ingredientId\":" + ingredientId
                + ",\"actualQuantity\":" + actualQuantity + "}]}";
        var response = mockMvc.perform(post("/stock-takes").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    @Test
    void submit_snapshotsExpectedUnitCostAndVariance() throws Exception {
        var riceId = createIngredient("Snapshot Rice");
        receiveIntoMainStore(riceId, "20.0000", "5.00");

        var id = submitStockTake(stockClerkToken, mainStoreId, riceId, "25.0000");

        mockMvc.perform(get("/stock-takes/" + id).header("Authorization", "Bearer " + stockAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.lines[0].expectedQuantity").value(20.0))
                .andExpect(jsonPath("$.lines[0].actualQuantity").value(25.0))
                .andExpect(jsonPath("$.lines[0].unitCost").value(5.0))
                .andExpect(jsonPath("$.lines[0].varianceQuantity").value(5.0))
                .andExpect(jsonPath("$.lines[0].varianceValue").value(25.0))
                .andExpect(jsonPath("$.lines[0].appliedAdjustmentQuantity").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void approve_withNoInterveningActivity_appliesExactOriginalVariance() throws Exception {
        var riceId = createIngredient("No Drift Rice");
        receiveIntoMainStore(riceId, "20.0000", "5.00");
        var id = submitStockTake(stockClerkToken, mainStoreId, riceId, "25.0000");

        mockMvc.perform(post("/stock-takes/" + id + "/review").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.lines[0].appliedAdjustmentQuantity").value(5.0));

        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Main Store')].stock").value(25.0));
    }

    @Test
    void approve_withInterveningGrv_recomputesDelta_landsExactlyOnActualQuantity() throws Exception {
        var riceId = createIngredient("Drift Rice");
        receiveIntoMainStore(riceId, "20.0000", "5.00");
        var id = submitStockTake(stockClerkToken, mainStoreId, riceId, "25.0000"); // variance snapshot: +5

        // Intervening activity after submission, before review.
        receiveIntoMainStore(riceId, "10.0000", "5.00"); // Main Store now 30

        mockMvc.perform(post("/stock-takes/" + id + "/review").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                // Original snapshot untouched...
                .andExpect(jsonPath("$.lines[0].varianceQuantity").value(5.0))
                // ...but the applied delta is recomputed against current stock (30), not the snapshot.
                .andExpect(jsonPath("$.lines[0].appliedAdjustmentQuantity").value(-5.0));

        // The ledger lands exactly on actualQuantity (25), regardless of the intervening GRV.
        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Main Store')].stock").value(25.0));
    }

    @Test
    void reject_writesNoMovement_statusRejected_noteStored() throws Exception {
        var riceId = createIngredient("Reject Rice");
        receiveIntoMainStore(riceId, "20.0000", "5.00");
        var id = submitStockTake(stockClerkToken, mainStoreId, riceId, "25.0000");

        mockMvc.perform(post("/stock-takes/" + id + "/review").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"note\":\"Recount needed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.note").value("Recount needed"))
                .andExpect(jsonPath("$.lines[0].appliedAdjustmentQuantity").value(org.hamcrest.Matchers.nullValue()));

        // Stock unchanged — the GRV's 20 is all that's there, no STOCK_TAKE_ADJUSTMENT applied.
        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Main Store')].stock").value(20.0));
    }

    @Test
    void kitchenStockTake_approve_leavesMainStoreUntouched() throws Exception {
        var riceId = createIngredient("Kitchen Take Rice");
        receiveIntoMainStore(riceId, "20.0000", "5.00");
        issueToKitchen(riceId, "10.0000"); // Main Store 10, Kitchen 10

        var id = submitStockTake(stockClerkToken, kitchenId, riceId, "8.0000"); // Kitchen short by 2

        mockMvc.perform(post("/stock-takes/" + id + "/review").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Kitchen')].stock").value(8.0))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Main Store')].stock").value(10.0));
    }

    @Test
    void mainStoreStockTake_approve_leavesKitchenUntouched() throws Exception {
        var riceId = createIngredient("Main Store Take Rice");
        receiveIntoMainStore(riceId, "20.0000", "5.00");
        issueToKitchen(riceId, "10.0000"); // Main Store 10, Kitchen 10

        var id = submitStockTake(stockClerkToken, mainStoreId, riceId, "9.0000"); // Main Store short by 1

        mockMvc.perform(post("/stock-takes/" + id + "/review").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Main Store')].stock").value(9.0))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Kitchen')].stock").value(10.0));
    }

    @Test
    void stockClerk_canSubmit_butForbiddenFromReview() throws Exception {
        var riceId = createIngredient("Clerk Submit Rice");
        receiveIntoMainStore(riceId, "20.0000", "5.00");
        var id = submitStockTake(stockClerkToken, mainStoreId, riceId, "25.0000");

        mockMvc.perform(post("/stock-takes/" + id + "/review").header("Authorization", "Bearer " + stockClerkToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void review_onAlreadyReviewed_returns409() throws Exception {
        var riceId = createIngredient("Already Reviewed Rice");
        receiveIntoMainStore(riceId, "20.0000", "5.00");
        var id = submitStockTake(stockClerkToken, mainStoreId, riceId, "25.0000");

        var approveBody = "{\"decision\":\"APPROVE\"}";
        mockMvc.perform(post("/stock-takes/" + id + "/review").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isOk());

        mockMvc.perform(post("/stock-takes/" + id + "/review").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isConflict());
    }

    @Test
    void stockClerk_cannotReadAnotherUsersStockTake() throws Exception {
        var riceId = createIngredient("Ownership Rice");
        receiveIntoMainStore(riceId, "20.0000", "5.00");
        var id = submitStockTake(adminToken, mainStoreId, riceId, "25.0000");

        mockMvc.perform(get("/stock-takes/" + id).header("Authorization", "Bearer " + stockClerkToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/stock-takes/" + id).header("Authorization", "Bearer " + stockAdminToken))
                .andExpect(status().isOk());
    }

    @Test
    void createStockTake_withoutLocationId_returns400() throws Exception {
        var riceId = createIngredient("No Location Take Rice");
        var body = "{\"lines\":[{\"ingredientId\":" + riceId + ",\"actualQuantity\":5}]}";
        mockMvc.perform(post("/stock-takes").header("Authorization", "Bearer " + stockClerkToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
