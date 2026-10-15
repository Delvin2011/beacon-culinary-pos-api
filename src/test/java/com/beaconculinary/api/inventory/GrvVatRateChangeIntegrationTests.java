package com.beaconculinary.api.inventory;

import com.beaconculinary.api.support.AuthTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GRV VAT handling: changing the configured standard rate affects only GRVs captured afterwards;
 * earlier lines keep the rate snapshotted when they were captured. */
@SpringBootTest(properties = "vat.standard-rate=0.16")
@AutoConfigureMockMvc
class GrvVatRateChangeIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private GrvRepository grvRepository;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;
    @Autowired
    private CountSheetCategoryRepository countSheetCategoryRepository;

    private Long ingredientId;

    @AfterEach
    void tearDown() {
        ingredientStockMovementRepository.findAll().stream()
                .filter(m -> ingredientId.equals(m.getIngredient().getId()))
                .forEach(ingredientStockMovementRepository::delete);
        grvRepository.search(ingredientId, null, null, null).forEach(grvRepository::delete);
        ingredientRepository.deleteById(ingredientId);
    }

    @Test
    void newGrvsUseTheNewRate_earlierLinesKeepTheirSnapshot() throws Exception {
        var adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        var ingredient = new Ingredient();
        ingredient.setName("VAT Rate Test Beef");
        ingredient.setUnit(IngredientUnit.KG);
        ingredient.setCountSheetCategory(countSheetCategoryRepository.findByNameIgnoreCase("MEAT").orElseThrow());
        ingredientId = ingredientRepository.save(ingredient).getId();

        // A line captured earlier at 15%: 10 x 100.00 excl -> 150.00 VAT -> 1150.00, cost 115.0000.
        var earlierGrvId = jdbc.queryForObject("INSERT INTO grv (invoice_number, supplier_name, received_by, prices_include_vat) "
                + "OUTPUT INSERTED.id VALUES ('INV-AT-15', 'Acme Foods', ?, 0)", Long.class, AuthTestHelper.adminId(mockMvc));
        jdbc.update("INSERT INTO grv_lines (grv_id, ingredient_id, quantity_received, cost_per_unit, unit_price_as_invoiced, "
                + "vat_code, vat_rate, unit_cost_excl_vat, unit_cost_incl_vat, line_excl_vat, line_vat, line_incl_vat) "
                + "VALUES (?, ?, 10, 115.0000, 100.0000, 'STANDARD', 0.1500, 100.0000, 115.0000, 1000.00, 150.00, 1150.00)",
                earlierGrvId, ingredientId);

        mockMvc.perform(get("/admin/settings/vat").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.standardRate").value(0.16));

        // Captured now at 16%: 10 x 100.00 excl -> 160.00 VAT -> 1160.00, cost 116.0000.
        mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceNumber\":\"INV-AT-16\",\"pricesIncludeVat\":false,\"supplierName\":\"Acme Foods\","
                                + "\"lines\":[{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":10,\"unitPrice\":100.00}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].vatRate").value(0.16))
                .andExpect(jsonPath("$.lines[0].lineVat").value(160.00))
                .andExpect(jsonPath("$.lines[0].costPerUnit").value(116.00));

        mockMvc.perform(get("/admin/grv/" + earlierGrvId).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.lines[0].vatRate").value(0.15))
                .andExpect(jsonPath("$.lines[0].lineVat").value(150.00))
                .andExpect(jsonPath("$.lines[0].costPerUnit").value(115.00))
                .andExpect(jsonPath("$.totalInclVat").value(1150.00));
    }
}
