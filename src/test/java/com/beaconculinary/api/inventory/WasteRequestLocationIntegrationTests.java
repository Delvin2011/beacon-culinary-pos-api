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

/** Stage 5.2.4: the STOCK_CLERK -&gt; STOCK_ADMIN WASTE request path is now location-aware
 * (header-level, chosen at submission), alongside the direct waste path already covered in
 * {@code WasteAndStockTakeListIntegrationTests}. */
@SpringBootTest
@AutoConfigureMockMvc
class WasteRequestLocationIntegrationTests {
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

    private void receiveIntoMainStore(long ingredientId, String quantity) throws Exception {
        var body = "{\"invoiceNumber\":\"INV-" + ingredientId + "\",\"supplierName\":\"Supplier A\","
                + "\"lines\":[{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":" + quantity + ",\"costPerUnit\":1.00}]}";
        mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void createWasteRequest_withoutLocationId_returns400() throws Exception {
        var riceId = createIngredient("No Location Waste Rice");
        var body = "{\"requestType\":\"WASTE\",\"lines\":[{\"ingredientId\":" + riceId + ",\"quantity\":5,\"reason\":\"Spoiled\"}]}";
        mockMvc.perform(post("/stock-requests").header("Authorization", "Bearer " + stockClerkToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWasteRequest_withUnknownLocationId_returns400() throws Exception {
        var riceId = createIngredient("Unknown Location Waste Rice");
        var body = "{\"requestType\":\"WASTE\",\"locationId\":999999999,\"lines\":[{\"ingredientId\":" + riceId
                + ",\"quantity\":5,\"reason\":\"Spoiled\"}]}";
        mockMvc.perform(post("/stock-requests").header("Authorization", "Bearer " + stockClerkToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wasteRequest_submittedAndApprovedAgainstMainStore_decreasesMainStoreOnly() throws Exception {
        var riceId = createIngredient("Main Store Waste Request Rice");
        receiveIntoMainStore(riceId, "20.0000");

        var createBody = "{\"requestType\":\"WASTE\",\"locationId\":" + mainStoreId
                + ",\"lines\":[{\"ingredientId\":" + riceId + ",\"quantity\":6,\"reason\":\"Spoiled\"}]}";
        var response = mockMvc.perform(post("/stock-requests").header("Authorization", "Bearer " + stockClerkToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locationId").value(mainStoreId))
                .andExpect(jsonPath("$.locationName").value("Main Store"))
                .andReturn().getResponse().getContentAsString();
        var id = MAPPER.readTree(response).get("id").asLong();

        mockMvc.perform(post("/stock-requests/" + id + "/action").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":" + riceId + ",\"actionedQuantity\":6}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIONED"));

        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Main Store')].stock").value(14.0))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Kitchen')].stock").value(0));

        // List view surfaces the location too.
        mockMvc.perform(get("/stock-requests").param("type", "WASTE").header("Authorization", "Bearer " + stockAdminToken))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].locationName").value("Main Store"));
    }

    @Test
    void wasteRequest_submittedAndApprovedAgainstKitchen_decreasesKitchenOnly() throws Exception {
        var riceId = createIngredient("Kitchen Waste Request Rice");
        // Get stock into Kitchen via the real Issue flow (Main Store -> Kitchen), then waste it
        // from there — exercising the full cross-stage path rather than seeding the ledger
        // directly.
        receiveIntoMainStore(riceId, "20.0000");
        var issueBody = "{\"requestType\":\"ISSUE\",\"lines\":[{\"ingredientId\":" + riceId + ",\"quantity\":10}]}";
        var issueResponse = mockMvc.perform(post("/stock-requests").header("Authorization", "Bearer " + stockClerkToken)
                        .contentType(MediaType.APPLICATION_JSON).content(issueBody))
                .andReturn().getResponse().getContentAsString();
        var issueId = MAPPER.readTree(issueResponse).get("id").asLong();
        mockMvc.perform(post("/stock-requests/" + issueId + "/action").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":" + riceId + ",\"actionedQuantity\":10}]}"))
                .andExpect(status().isOk());

        var wasteBody = "{\"requestType\":\"WASTE\",\"locationId\":" + kitchenId
                + ",\"lines\":[{\"ingredientId\":" + riceId + ",\"quantity\":4,\"reason\":\"Kitchen spoilage\"}]}";
        var wasteResponse = mockMvc.perform(post("/stock-requests").header("Authorization", "Bearer " + stockClerkToken)
                        .contentType(MediaType.APPLICATION_JSON).content(wasteBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locationName").value("Kitchen"))
                .andReturn().getResponse().getContentAsString();
        var wasteId = MAPPER.readTree(wasteResponse).get("id").asLong();

        mockMvc.perform(post("/stock-requests/" + wasteId + "/action").header("Authorization", "Bearer " + stockAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"ingredientId\":" + riceId + ",\"actionedQuantity\":4}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Kitchen')].stock").value(6.0))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Main Store')].stock").value(10.0));
    }
}
