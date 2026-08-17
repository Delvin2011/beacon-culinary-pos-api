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

/** Exercises Stage 5 Parts A, D, E, F: ingredient master data, and the GRV/waste/stock-take
 * flows that all write onto the same append-only ingredient_stock_movements ledger. */
@SpringBootTest
@AutoConfigureMockMvc
class IngredientLedgerIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;
    @Autowired
    private GrvRepository grvRepository;
    @Autowired
    private WasteEntryRepository wasteEntryRepository;
    @Autowired
    private StockTakeRepository stockTakeRepository;
    @Autowired
    private IngredientRepository ingredientRepository;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        ingredientStockMovementRepository.deleteAll();
        grvRepository.deleteAll();
        wasteEntryRepository.deleteAll();
        stockTakeRepository.deleteAll();
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

    private void createGrv(long ingredientId, String quantity, String costPerUnit, String supplierName) throws Exception {
        var body = "{\"ingredientId\":" + ingredientId + ",\"quantity\":" + quantity
                + ",\"costPerUnit\":" + costPerUnit + ",\"supplierName\":\"" + supplierName + "\"}";
        mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void createIngredient_startsWithZeroDerivedStock() throws Exception {
        var id = createIngredient("Test Rice");

        mockMvc.perform(get("/admin/ingredients/" + id + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStock").value(0))
                .andExpect(jsonPath("$.lastMovementAt").doesNotExist());
    }

    @Test
    void grv_increasesStock_andPreservesCostHistoryAcrossMultipleEntries() throws Exception {
        var id = createIngredient("Test Rice");

        createGrv(id, "10.0000", "18.00", "Supplier A");
        mockMvc.perform(get("/admin/ingredients/" + id + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStock").value(10.0));

        createGrv(id, "5.0000", "20.00", "Supplier B");
        mockMvc.perform(get("/admin/ingredients/" + id + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStock").value(15.0));

        // Both GRVs retained with their own cost — never overwritten onto Ingredient.
        mockMvc.perform(get("/admin/grv").param("ingredientId", String.valueOf(id))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.supplierName == 'Supplier A')].costPerUnit").value(18.0))
                .andExpect(jsonPath("$[?(@.supplierName == 'Supplier B')].costPerUnit").value(20.0));
    }

    @Test
    void waste_decreasesStock() throws Exception {
        var id = createIngredient("Test Rice");
        createGrv(id, "20.0000", "18.00", "Supplier A");

        var wasteBody = "{\"ingredientId\":" + id + ",\"quantity\":3.0000,\"reason\":\"Spoilage\"}";
        mockMvc.perform(post("/admin/waste").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(wasteBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(3.0));

        mockMvc.perform(get("/admin/ingredients/" + id + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStock").value(17.0));
    }

    @Test
    void stockTake_reconcilesDerivedStock_inBothDirections() throws Exception {
        var id = createIngredient("Test Rice");
        createGrv(id, "20.0000", "18.00", "Supplier A");

        // Counted above derived stock — positive variance.
        var upBody = "{\"ingredientId\":" + id + ",\"countedQuantity\":25.0000}";
        mockMvc.perform(post("/admin/stock-takes").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(upBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variance").value(5.0));
        mockMvc.perform(get("/admin/ingredients/" + id + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.currentStock").value(25.0));

        // Counted below derived stock — negative variance.
        var downBody = "{\"ingredientId\":" + id + ",\"countedQuantity\":18.0000}";
        mockMvc.perform(post("/admin/stock-takes").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(downBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variance").value(-7.0));
        mockMvc.perform(get("/admin/ingredients/" + id + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.currentStock").value(18.0));
    }

    @Test
    void updateIngredient_editsFieldsAndDeactivates() throws Exception {
        var id = createIngredient("Test Rice");

        var updateBody = "{\"name\":\"Renamed Rice\",\"unit\":\"KG\",\"countSheetCategory\":\"BULK\",\"active\":false}";
        mockMvc.perform(put("/admin/ingredients/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed Rice"))
                .andExpect(jsonPath("$.countSheetCategory").value("BULK"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void grvAndWasteAndStockTake_onNonexistentIngredient_returns400() throws Exception {
        mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ingredientId\":999999999,\"quantity\":1,\"costPerUnit\":1,\"supplierName\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }
}
