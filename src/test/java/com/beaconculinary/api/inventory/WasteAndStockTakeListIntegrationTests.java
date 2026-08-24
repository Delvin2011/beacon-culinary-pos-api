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

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /admin/waste and GET /admin/stock-takes — read-only list views over WasteEntry/StockTake,
 * the Stage 5 follow-up that closes the read side POST /admin/waste and POST /admin/stock-takes
 * already had a write side for. */
@SpringBootTest
@AutoConfigureMockMvc
class WasteAndStockTakeListIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private WasteEntryRepository wasteEntryRepository;
    @Autowired
    private LegacyStockTakeRepository stockTakeRepository;
    @Autowired
    private GrvRepository grvRepository;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;

    private String adminToken;
    private long riceId;
    private long oilId;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        riceId = createIngredient("Waste List Test Rice");
        oilId = createIngredient("Waste List Test Oil");
    }

    // Scoped to exactly the two ingredients this test class creates (not a blanket deleteAll)
    // since this suite runs against a shared dev database.
    @AfterEach
    void tearDown() {
        for (long id : List.of(riceId, oilId)) {
            ingredientStockMovementRepository.findAll().stream()
                    .filter(m -> id == m.getIngredient().getId())
                    .forEach(ingredientStockMovementRepository::delete);
            wasteEntryRepository.findByIngredientIdOrderByCreatedAtDesc(id).forEach(wasteEntryRepository::delete);
            stockTakeRepository.findByIngredientIdOrderByCreatedAtDesc(id).forEach(stockTakeRepository::delete);
            grvRepository.search(id, null, null, null).forEach(grvRepository::delete);
            ingredientRepository.deleteById(id);
        }
    }

    private long createIngredient(String name) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"unit\":\"KG\",\"countSheetCategory\":\"DRYSTOCK\"}";
        var response = mockMvc.perform(post("/admin/ingredients").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private void createGrv(long ingredientId, String quantity) throws Exception {
        var body = "{\"invoiceNumber\":\"INV-" + ingredientId + "-" + quantity + "\",\"supplierName\":\"Test Supplier\","
                + "\"lines\":[{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":" + quantity + ",\"costPerUnit\":10}]}";
        mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
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

    private void createWaste(long ingredientId, String quantity, String reason) throws Exception {
        var body = "{\"ingredientId\":" + ingredientId + ",\"locationId\":" + mainStoreLocationId()
                + ",\"quantity\":" + quantity + ",\"reason\":\"" + reason + "\"}";
        mockMvc.perform(post("/admin/waste").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void createStockTake(long ingredientId, String countedQuantity) throws Exception {
        var body = "{\"ingredientId\":" + ingredientId + ",\"countedQuantity\":" + countedQuantity + "}";
        mockMvc.perform(post("/admin/stock-takes").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void listWaste_returnsAllEntriesNewestFirst() throws Exception {
        createGrv(riceId, "50");
        createWaste(riceId, "3", "Spoiled overnight");
        createWaste(oilId, "1", "Spilled");

        mockMvc.perform(get("/admin/waste").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].ingredientName").value("Waste List Test Oil"))
                .andExpect(jsonPath("$.entries[0].reason").value("Spilled"))
                .andExpect(jsonPath("$.entries[0].recordedBy").isNotEmpty())
                .andExpect(jsonPath("$.entries[0].locationName").value("Main Store"))
                .andExpect(jsonPath("$.entries[1].ingredientName").value("Waste List Test Rice"))
                .andExpect(jsonPath("$.entries[1].quantity").value(3))
                .andExpect(jsonPath("$.entries[1].reason").value("Spoiled overnight"));
    }

    @Test
    void createWaste_withoutLocationId_returns400() throws Exception {
        var body = "{\"ingredientId\":" + riceId + ",\"quantity\":1,\"reason\":\"No location\"}";
        mockMvc.perform(post("/admin/waste").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWaste_atKitchen_leavesMainStoreUntouched() throws Exception {
        createGrv(riceId, "50");

        var kitchenId = mockMvc.perform(get("/locations").header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        long kitchenLocationId = -1;
        for (var node : MAPPER.readTree(kitchenId)) {
            if (node.get("name").asText().equalsIgnoreCase("Kitchen")) {
                kitchenLocationId = node.get("id").asLong();
            }
        }

        var body = "{\"ingredientId\":" + riceId + ",\"locationId\":" + kitchenLocationId
                + ",\"quantity\":3,\"reason\":\"Kitchen spoilage\"}";
        mockMvc.perform(post("/admin/waste").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locationName").value("Kitchen"));

        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Kitchen')].stock").value(-3.0))
                .andExpect(jsonPath("$.byLocation[?(@.locationName == 'Main Store')].stock").value(50.0));
    }

    @Test
    void listWaste_filtersByIngredientId() throws Exception {
        createGrv(riceId, "50");
        createGrv(oilId, "50");
        createWaste(riceId, "3", "Rice waste");
        createWaste(oilId, "1", "Oil waste");

        mockMvc.perform(get("/admin/waste").param("ingredientId", String.valueOf(riceId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].ingredientName").value("Waste List Test Rice"));
    }

    @Test
    void listWaste_filtersByDateRange() throws Exception {
        createGrv(riceId, "50");
        createWaste(riceId, "3", "Rice waste");

        // Scoped to riceId as well as the date range — the dev DB this suite runs against can
        // hold other ingredients' waste entries within any wide, unscoped date window.
        var farFuture = java.time.LocalDateTime.now().plusYears(1);
        mockMvc.perform(get("/admin/waste")
                        .param("ingredientId", String.valueOf(riceId))
                        .param("from", farFuture.toString())
                        .param("to", farFuture.plusDays(1).toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));

        var farPast = java.time.LocalDateTime.now().minusYears(1);
        mockMvc.perform(get("/admin/waste")
                        .param("ingredientId", String.valueOf(riceId))
                        .param("from", farPast.toString())
                        .param("to", farFuture.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    void listWaste_emptyResult_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/admin/waste").param("ingredientId", String.valueOf(riceId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void listWaste_nonAdminCallers_areRejected() throws Exception {
        mockMvc.perform(get("/admin/waste")).andExpect(status().isUnauthorized());

        var cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        mockMvc.perform(get("/admin/waste").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void listStockTakes_reportsStoredExpectedAndVariance_bothDirections() throws Exception {
        createGrv(riceId, "20");
        createStockTake(riceId, "25"); // positive variance: 25 - 20 = 5
        createGrv(oilId, "20");
        createStockTake(oilId, "18"); // negative variance: 18 - 20 = -2

        mockMvc.perform(get("/admin/stock-takes").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.ingredientName == 'Waste List Test Rice')].countedQuantity").value(25.0))
                .andExpect(jsonPath("$.entries[?(@.ingredientName == 'Waste List Test Rice')].expectedQuantity").value(20.0))
                .andExpect(jsonPath("$.entries[?(@.ingredientName == 'Waste List Test Rice')].variance").value(5.0))
                .andExpect(jsonPath("$.entries[?(@.ingredientName == 'Waste List Test Oil')].countedQuantity").value(18.0))
                .andExpect(jsonPath("$.entries[?(@.ingredientName == 'Waste List Test Oil')].expectedQuantity").value(20.0))
                .andExpect(jsonPath("$.entries[?(@.ingredientName == 'Waste List Test Oil')].variance").value(-2.0));
    }

    @Test
    void listStockTakes_varianceStaysHistorical_evenAfterLaterMovements() throws Exception {
        createGrv(riceId, "20");
        createStockTake(riceId, "25"); // variance 5, recorded when stock was 20 -> 25

        // A GRV recorded after the stock take must not change what the stock take reports.
        createGrv(riceId, "100");

        mockMvc.perform(get("/admin/stock-takes").param("ingredientId", String.valueOf(riceId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].countedQuantity").value(25.0))
                .andExpect(jsonPath("$.entries[0].expectedQuantity").value(20.0))
                .andExpect(jsonPath("$.entries[0].variance").value(5.0));
    }

    @Test
    void listStockTakes_filtersByIngredientId() throws Exception {
        createGrv(riceId, "20");
        createStockTake(riceId, "25");
        createGrv(oilId, "20");
        createStockTake(oilId, "18");

        mockMvc.perform(get("/admin/stock-takes").param("ingredientId", String.valueOf(oilId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].ingredientName").value("Waste List Test Oil"));
    }

    @Test
    void listStockTakes_nonAdminCallers_areRejected() throws Exception {
        mockMvc.perform(get("/admin/stock-takes")).andExpect(status().isUnauthorized());

        var cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        mockMvc.perform(get("/admin/stock-takes").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }
}
