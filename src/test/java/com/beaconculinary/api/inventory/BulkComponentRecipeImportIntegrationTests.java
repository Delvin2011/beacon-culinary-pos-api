package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.ComponentCatalog;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BulkComponentRecipeImportIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ComponentCatalogRepository componentCatalogRepository;
    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private IngredientRepository ingredientRepository;

    private String adminToken;

    private static final String COMPONENT_NAME = "Bulk Recipe Import Beef Stew";
    private static final List<String> INGREDIENT_NAMES =
            List.of("Bulk Recipe Import Beef Chuck", "Bulk Recipe Import Onion", "Bulk Recipe Import Bad Unit");

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    // Recipes are a FK child of component_catalog, so they must be deleted first. Scoped to
    // exactly the rows this test class creates (not a blanket deleteAll) since this suite runs
    // against a shared dev database.
    @AfterEach
    void tearDown() {
        componentCatalogRepository.findByNameIgnoreCase(COMPONENT_NAME).ifPresent(component -> {
            recipeRepository.findWithLinesByComponentCatalogId(component.getId()).ifPresent(recipeRepository::delete);
            componentCatalogRepository.delete(component);
        });
        INGREDIENT_NAMES.forEach(name ->
                ingredientRepository.findByNameIgnoreCase(name).ifPresent(ingredientRepository::delete));
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "components.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void bulkImport_createsNewComponentRecipeAndIngredients() throws Exception {
        var file = csv("""
                COMPONENT,INGREDIENT (NAME),UNIT,COUNT SHEET,Quantities,Batch Size,Per Portion Price (R)
                Bulk Recipe Import Beef Stew,Bulk Recipe Import Beef Chuck,kg,bulk,2.5,10,25
                Bulk Recipe Import Beef Stew,Bulk Recipe Import Onion,kg,fveg,0.8,10,25
                """);

        mockMvc.perform(multipart("/admin/component-catalog/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.componentsCreated").value(1))
                .andExpect(jsonPath("$.componentsUpdated").value(0))
                .andExpect(jsonPath("$.ingredientsCreated").value(2))
                .andExpect(jsonPath("$.ingredientsUpdated").value(0))
                .andExpect(jsonPath("$.components.length()").value(1))
                .andExpect(jsonPath("$.components[0].componentName").value(COMPONENT_NAME))
                .andExpect(jsonPath("$.components[0].extraPrice").value(25))
                .andExpect(jsonPath("$.components[0].batchSize").value(10))
                .andExpect(jsonPath("$.components[0].lines.length()").value(2))
                .andExpect(jsonPath("$.components[0].lines[?(@.ingredientName == 'Bulk Recipe Import Beef Chuck')].quantity")
                        .value(2.5));

        var component = componentCatalogRepository.findByNameIgnoreCase(COMPONENT_NAME).orElseThrow();
        assertThat(component.getExtraPrice()).isEqualByComparingTo(new BigDecimal("25"));
        var recipe = recipeRepository.findWithLinesByComponentCatalogId(component.getId()).orElseThrow();
        assertThat(recipe.getBatchSize()).isEqualTo(10);
        assertThat(recipe.getLines()).hasSize(2);
        assertThat(ingredientRepository.findByNameIgnoreCase("Bulk Recipe Import Beef Chuck")).isPresent();
    }

    @Test
    void bulkImport_upsertsExistingComponentAndIngredientByName() throws Exception {
        var existingComponent = new ComponentCatalog();
        existingComponent.setName(COMPONENT_NAME);
        existingComponent.setExtraPrice(new BigDecimal("20"));
        existingComponent.setActive(true);
        var existingComponentId = componentCatalogRepository.save(existingComponent).getId();

        var existingIngredient = new Ingredient();
        existingIngredient.setName("Bulk Recipe Import Beef Chuck");
        existingIngredient.setUnit(IngredientUnit.EACH);
        existingIngredient.setCountSheetCategory(CountSheetCategory.PREP);
        var existingIngredientId = ingredientRepository.save(existingIngredient).getId();

        var file = csv("""
                COMPONENT,INGREDIENT (NAME),UNIT,COUNT SHEET,Quantities,Batch Size,Per Portion Price (R)
                bulk recipe import beef stew,bulk recipe import beef chuck,kg,bulk,3,10,30
                """);

        mockMvc.perform(multipart("/admin/component-catalog/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.componentsCreated").value(0))
                .andExpect(jsonPath("$.componentsUpdated").value(1))
                .andExpect(jsonPath("$.ingredientsCreated").value(0))
                .andExpect(jsonPath("$.ingredientsUpdated").value(1));

        var component = componentCatalogRepository.findByNameIgnoreCase(COMPONENT_NAME).orElseThrow();
        assertThat(component.getId()).isEqualTo(existingComponentId);
        assertThat(component.getExtraPrice()).isEqualByComparingTo(new BigDecimal("30"));

        var ingredient = ingredientRepository.findByNameIgnoreCase("Bulk Recipe Import Beef Chuck").orElseThrow();
        assertThat(ingredient.getId()).isEqualTo(existingIngredientId);
        assertThat(ingredient.getUnit()).isEqualTo(IngredientUnit.KG);
        assertThat(ingredient.getCountSheetCategory()).isEqualTo(CountSheetCategory.BULK);
    }

    @Test
    void bulkImport_inconsistentBatchSizeForSameComponent_rejectsWholeFileAndSavesNothing() throws Exception {
        var file = csv("""
                COMPONENT,INGREDIENT (NAME),UNIT,COUNT SHEET,Quantities,Batch Size,Per Portion Price (R)
                Bulk Recipe Import Beef Stew,Bulk Recipe Import Beef Chuck,kg,bulk,2.5,10,25
                Bulk Recipe Import Beef Stew,Bulk Recipe Import Onion,kg,fveg,0.8,5,25
                """);

        mockMvc.perform(multipart("/admin/component-catalog/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("batch size")));

        assertThat(componentCatalogRepository.findByNameIgnoreCase(COMPONENT_NAME)).isEmpty();
        assertThat(ingredientRepository.findByNameIgnoreCase("Bulk Recipe Import Beef Chuck")).isEmpty();
    }

    @Test
    void bulkImport_invalidRow_rejectsWholeFileAndSavesNothing() throws Exception {
        var file = csv("""
                COMPONENT,INGREDIENT (NAME),UNIT,COUNT SHEET,Quantities,Batch Size,Per Portion Price (R)
                Bulk Recipe Import Beef Stew,Bulk Recipe Import Beef Chuck,kg,bulk,2.5,10,25
                Bulk Recipe Import Beef Stew,Bulk Recipe Import Bad Unit,kgs,bulk,0.8,10,25
                """);

        mockMvc.perform(multipart("/admin/component-catalog/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("Row 3")));

        assertThat(componentCatalogRepository.findByNameIgnoreCase(COMPONENT_NAME)).isEmpty();
        assertThat(ingredientRepository.findByNameIgnoreCase("Bulk Recipe Import Beef Chuck")).isEmpty();
    }

    @Test
    void bulkImport_missingRequiredColumn_returns400() throws Exception {
        var file = csv("""
                COMPONENT,INGREDIENT (NAME),UNIT,Quantities,Batch Size,Per Portion Price (R)
                Bulk Recipe Import Beef Stew,Bulk Recipe Import Beef Chuck,kg,2.5,10,25
                """);

        mockMvc.perform(multipart("/admin/component-catalog/bulk-import").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("Count Sheet")));
    }
}
