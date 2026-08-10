package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.ComponentCatalogRepository;
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

/** Exercises Stage 5 Part B: component-level recipes (batch bill of materials). */
@SpringBootTest
@AutoConfigureMockMvc
class RecipeIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private ComponentCatalogRepository componentCatalogRepository;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;
    @Autowired
    private GrvRepository grvRepository;
    @Autowired
    private IngredientRepository ingredientRepository;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        ingredientStockMovementRepository.deleteAll();
        grvRepository.deleteAll();
        ingredientRepository.deleteAll();
    }

    private long createIngredient(String name, String unit) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"unit\":\"" + unit + "\",\"countSheetCategory\":\"DRYSTOCK\"}";
        var response = mockMvc.perform(post("/admin/ingredients").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long createComponent(String name) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"extraPrice\":10.00}";
        var response = mockMvc.perform(post("/admin/component-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    @Test
    void componentWithNoRecipe_returns404() throws Exception {
        var componentId = createComponent("Rice");

        mockMvc.perform(get("/admin/components/" + componentId + "/recipe").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void createRecipe_withMultipleLines_matchesOnGet() throws Exception {
        var componentId = createComponent("Rice");
        var riceId = createIngredient("Rice", "KG");
        var oilId = createIngredient("Cooking Oil", "LITRE");
        var saltId = createIngredient("Salt", "KG");

        var body = "{\"batchSize\":10,\"lines\":["
                + "{\"ingredientId\":" + riceId + ",\"quantity\":1.5},"
                + "{\"ingredientId\":" + oilId + ",\"quantity\":0.25},"
                + "{\"ingredientId\":" + saltId + ",\"quantity\":0.005}]}";
        mockMvc.perform(put("/admin/components/" + componentId + "/recipe").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchSize").value(10))
                .andExpect(jsonPath("$.lines.length()").value(3));

        mockMvc.perform(get("/admin/components/" + componentId + "/recipe").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.componentCatalogId").value(componentId))
                .andExpect(jsonPath("$.batchSize").value(10))
                .andExpect(jsonPath("$.lines[?(@.ingredientId == " + riceId + ")].quantity").value(1.5))
                .andExpect(jsonPath("$.lines[?(@.ingredientId == " + oilId + ")].quantity").value(0.25))
                .andExpect(jsonPath("$.lines[?(@.ingredientId == " + saltId + ")].quantity").value(0.005));
    }

    @Test
    void updateRecipe_replacesLinesWholesale() throws Exception {
        var componentId = createComponent("Rice");
        var riceId = createIngredient("Rice", "KG");
        var oilId = createIngredient("Cooking Oil", "LITRE");

        var firstBody = "{\"batchSize\":10,\"lines\":["
                + "{\"ingredientId\":" + riceId + ",\"quantity\":1.5},"
                + "{\"ingredientId\":" + oilId + ",\"quantity\":0.25}]}";
        mockMvc.perform(put("/admin/components/" + componentId + "/recipe").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isOk());

        // Replace wholesale with a single line — the old oil line must be gone, not merged.
        var secondBody = "{\"batchSize\":20,\"lines\":[{\"ingredientId\":" + riceId + ",\"quantity\":3.0}]}";
        mockMvc.perform(put("/admin/components/" + componentId + "/recipe").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchSize").value(20))
                .andExpect(jsonPath("$.lines.length()").value(1));

        mockMvc.perform(get("/admin/components/" + componentId + "/recipe").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].ingredientId").value(riceId));
    }

    @Test
    void updateRecipe_withUnknownIngredientId_returns400() throws Exception {
        var componentId = createComponent("Rice");

        var body = "{\"batchSize\":10,\"lines\":[{\"ingredientId\":999999999,\"quantity\":1.5}]}";
        mockMvc.perform(put("/admin/components/" + componentId + "/recipe").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
