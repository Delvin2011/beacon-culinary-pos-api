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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BulkIngredientImportIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredientRepository;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    // Deliberately scoped to the exact names this test class creates rather than
    // ingredientRepository.deleteAll() — this suite runs against a shared dev database that can
    // hold real ingredients with GRV/waste/stock-take history, and a blanket deleteAll() would
    // both wipe that data and abort on its FK references.
    private static final List<String> TEST_INGREDIENT_NAMES =
            List.of("Chicken Portions", "Beef Chuck", "Bad Row");

    @AfterEach
    void tearDown() {
        TEST_INGREDIENT_NAMES.forEach(name ->
                ingredientRepository.findByNameIgnoreCase(name).ifPresent(ingredientRepository::delete));
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "ingredients.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void bulkImport_createsNewIngredients() throws Exception {
        var file = csv("""
                NAME,UNIT,COUNT SHEET
                Chicken Portions,KG,BULK
                Beef Chuck,KG,BULK
                """);

        mockMvc.perform(multipart("/admin/ingredients/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.ingredients.length()").value(2))
                .andExpect(jsonPath("$.ingredients[?(@.name == 'Chicken Portions')].unit").value("KG"))
                .andExpect(jsonPath("$.ingredients[?(@.name == 'Chicken Portions')].countSheetCategory").value("BULK"));

        assertThat(ingredientRepository.findByNameIgnoreCase("chicken portions")).isPresent();
    }

    @Test
    void bulkImport_upsertsExistingIngredientByNameCaseInsensitive() throws Exception {
        var ingredient = new Ingredient();
        ingredient.setName("Chicken Portions");
        ingredient.setUnit(IngredientUnit.EACH);
        ingredient.setCountSheetCategory(CountSheetCategory.PREP);
        var existingId = ingredientRepository.save(ingredient).getId();

        var file = csv("""
                NAME,UNIT,COUNT SHEET
                chicken portions,KG,BULK
                """);

        mockMvc.perform(multipart("/admin/ingredients/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(1));

        var updated = ingredientRepository.findByNameIgnoreCase("Chicken Portions").orElseThrow();
        assertThat(updated.getId()).isEqualTo(existingId);
        assertThat(updated.getUnit()).isEqualTo(IngredientUnit.KG);
        assertThat(updated.getCountSheetCategory()).isEqualTo(CountSheetCategory.BULK);
    }

    @Test
    void bulkImport_invalidRow_rejectsWholeFileAndSavesNothing() throws Exception {
        var file = csv("""
                NAME,UNIT,COUNT SHEET
                Chicken Portions,KG,BULK
                Bad Row,KGS,BULK
                """);

        mockMvc.perform(multipart("/admin/ingredients/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("Row 3")));

        assertThat(ingredientRepository.findByNameIgnoreCase("Chicken Portions")).isEmpty();
        assertThat(ingredientRepository.findByNameIgnoreCase("Bad Row")).isEmpty();
    }

    @Test
    void bulkImport_missingRequiredColumn_returns400() throws Exception {
        var file = csv("""
                NAME,UNIT
                Chicken Portions,KG
                """);

        mockMvc.perform(multipart("/admin/ingredients/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("COUNT SHEET")));
    }
}
