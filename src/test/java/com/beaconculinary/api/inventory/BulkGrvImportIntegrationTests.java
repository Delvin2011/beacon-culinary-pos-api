package com.beaconculinary.api.inventory;

import com.beaconculinary.api.support.AuthTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BulkGrvImportIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private GrvRepository grvRepository;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;

    private String adminToken;
    private Long chickenId;
    private Long beefId;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        chickenId = createIngredient("Bulk GRV Test Chicken");
        beefId = createIngredient("Bulk GRV Test Beef");
    }

    private Long createIngredient(String name) {
        var ingredient = new Ingredient();
        ingredient.setName(name);
        ingredient.setUnit(IngredientUnit.KG);
        ingredient.setCountSheetCategory(CountSheetCategory.BULK);
        return ingredientRepository.save(ingredient).getId();
    }

    // Scoped to exactly the two ingredients this test class creates (findAll+filter rather than
    // a blanket deleteAll) — this suite runs against a shared dev database that can hold real
    // GRV/stock-movement history on other ingredients, which must never be touched.
    @AfterEach
    void tearDown() {
        for (Long id : List.of(chickenId, beefId)) {
            ingredientStockMovementRepository.findAll().stream()
                    .filter(m -> id.equals(m.getIngredient().getId()))
                    .forEach(ingredientStockMovementRepository::delete);
            grvRepository.findByIngredientIdOrderByReceivedAtDesc(id).forEach(grvRepository::delete);
            ingredientRepository.deleteById(id);
        }
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "grv.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void bulkImport_createsGrvPerRow_andIncreasesStock() throws Exception {
        var file = csv("""
                Ingredient Name,Quantity,Cost Per Unit,Supplier Name,Note (Optional)
                Bulk GRV Test Chicken,80,42,SA Prime Meats & Poultry (Pty) Ltd,Weekly bulk order - IQF portions
                Bulk GRV Test Beef,60,95,SA Prime Meats & Poultry (Pty) Ltd,
                """);

        // Rows are processed in CSV order, so grvs[0] is the Chicken row and grvs[1] is Beef.
        mockMvc.perform(multipart("/admin/grv/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.grvs.length()").value(2))
                .andExpect(jsonPath("$.grvs[0].ingredientName").value("Bulk GRV Test Chicken"))
                .andExpect(jsonPath("$.grvs[0].quantity").value(80))
                .andExpect(jsonPath("$.grvs[0].costPerUnit").value(42))
                .andExpect(jsonPath("$.grvs[0].supplierName").value("SA Prime Meats & Poultry (Pty) Ltd"))
                .andExpect(jsonPath("$.grvs[0].note").value("Weekly bulk order - IQF portions"))
                .andExpect(jsonPath("$.grvs[1].ingredientName").value("Bulk GRV Test Beef"))
                .andExpect(jsonPath("$.grvs[1].note").value(nullValue()));

        mockMvc.perform(get("/admin/ingredients/" + chickenId + "/stock")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.currentStock").value(80));
        mockMvc.perform(get("/admin/ingredients/" + beefId + "/stock")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.currentStock").value(60));
    }

    @Test
    void bulkImport_unknownIngredient_rejectsWholeFileAndSavesNothing() throws Exception {
        var file = csv("""
                Ingredient Name,Quantity,Cost Per Unit,Supplier Name,Note (Optional)
                Bulk GRV Test Chicken,80,42,SA Prime Meats & Poultry (Pty) Ltd,
                Nonexistent Ingredient,60,95,SA Prime Meats & Poultry (Pty) Ltd,
                """);

        mockMvc.perform(multipart("/admin/grv/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("Nonexistent Ingredient")));

        assertThat(grvRepository.findByIngredientIdOrderByReceivedAtDesc(chickenId)).isEmpty();
    }

    @Test
    void bulkImport_invalidQuantity_returns400() throws Exception {
        var file = csv("""
                Ingredient Name,Quantity,Cost Per Unit,Supplier Name,Note (Optional)
                Bulk GRV Test Chicken,not-a-number,42,SA Prime Meats & Poultry (Pty) Ltd,
                """);

        mockMvc.perform(multipart("/admin/grv/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("quantity")));
    }

    @Test
    void bulkImport_worksWithoutOptionalNoteColumn() throws Exception {
        var file = csv("""
                Ingredient Name,Quantity,Cost Per Unit,Supplier Name
                Bulk GRV Test Chicken,80,42,SA Prime Meats & Poultry (Pty) Ltd
                """);

        mockMvc.perform(multipart("/admin/grv/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.grvs[0].note").value(nullValue()));
    }

    @Test
    void bulkImport_missingRequiredColumn_returns400() throws Exception {
        var file = csv("""
                Ingredient Name,Quantity,Supplier Name
                Bulk GRV Test Chicken,80,SA Prime Meats & Poultry (Pty) Ltd
                """);

        mockMvc.perform(multipart("/admin/grv/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("Cost Per Unit")));
    }
}
