package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
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

/** Exercises Stage 5 Part C: the consolidated, editable ingredient-requirements review step at
 * daily-planning time. Uses the rice/chicken worked example from the stage spec — Rice's and
 * Chicken's recipes both draw on Cooking Oil and Salt, so planning both exercises the
 * cross-component consolidation the spec calls out. */
@SpringBootTest
@AutoConfigureMockMvc
class DailyPlanningIngredientRequirementsIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLANNING_DATE = "2026-01-15";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private ComponentCatalogRepository componentCatalogRepository;
    @Autowired
    private MealCatalogRepository mealCatalogRepository;
    @Autowired
    private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired
    private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired
    private IngredientRequirementConfirmationRepository confirmationRepository;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;
    @Autowired
    private GrvRepository grvRepository;
    @Autowired
    private IngredientRepository ingredientRepository;

    private String adminToken;
    private long lunchId;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        lunchId = periodId("lunch");
    }

    @AfterEach
    void tearDown() {
        confirmationRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        ingredientStockMovementRepository.deleteAll();
        grvRepository.deleteAll();
        ingredientRepository.deleteAll();
    }

    private long periodId(String name) throws Exception {
        var response = mockMvc.perform(get("/meal-periods")).andReturn().getResponse().getContentAsString();
        for (var node : MAPPER.readTree(response)) {
            if (node.get("name").asText().equalsIgnoreCase(name)) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException(name + " period not seeded");
    }

    private long createIngredient(String name, String unit) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"unit\":\"" + unit + "\",\"countSheetCategory\":\"DRYSTOCK\"}";
        var response = mockMvc.perform(post("/admin/ingredients").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private void createGrv(long ingredientId, String quantity) throws Exception {
        var body = "{\"ingredientId\":" + ingredientId + ",\"quantity\":" + quantity
                + ",\"costPerUnit\":1.00,\"supplierName\":\"Supplier A\"}";
        mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private long createComponent(String name) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"extraPrice\":10.00}";
        var response = mockMvc.perform(post("/admin/component-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private void putRecipe(long componentId, int batchSize, String linesJson) throws Exception {
        var body = "{\"batchSize\":" + batchSize + ",\"lines\":" + linesJson + "}";
        mockMvc.perform(put("/admin/components/" + componentId + "/recipe").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private long createMealCatalog(String name, long componentId) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"description\":\"desc\",\"price\":50.00,\"componentIds\":[" + componentId + "]}";
        var response = mockMvc.perform(post("/admin/meal-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private void createDailyOption(long mealCatalogId, int plannedPortions) throws Exception {
        var body = "{\"mealPeriodId\":" + lunchId + ",\"optionDate\":\"" + PLANNING_DATE + "\",\"mealCatalogId\":" + mealCatalogId
                + ",\"plannedPortions\":" + plannedPortions + "}";
        mockMvc.perform(post("/admin/daily-options").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void createDailyComponentStock(long componentId, int bufferQuantity) throws Exception {
        var body = "{\"componentCatalogId\":" + componentId + ",\"mealPeriodId\":" + lunchId
                + ",\"optionDate\":\"" + PLANNING_DATE + "\",\"bufferQuantity\":" + bufferQuantity + "}";
        mockMvc.perform(post("/admin/daily-component-stock").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /** Sets up rice/chicken exactly as in the stage spec's worked example, split across two
     * different daily meal options (Rice-only, Chicken-only) plus a Rice component-stock buffer
     * — so Cooking Oil and Salt each get contributions from three different sources, via two
     * different components, and must consolidate into one line each rather than several. */
    private record Fixture(long riceId, long chickenId, long oilId, long saltId) {
    }

    private Fixture setUpRiceAndChickenPlan() throws Exception {
        var riceId = createIngredient("Rice", "KG");
        var chickenId = createIngredient("Chicken", "KG");
        var oilId = createIngredient("Cooking Oil", "LITRE");
        var saltId = createIngredient("Salt", "KG");

        var riceComponentId = createComponent("Rice");
        putRecipe(riceComponentId, 10, "[{\"ingredientId\":" + riceId + ",\"quantity\":1.5},"
                + "{\"ingredientId\":" + oilId + ",\"quantity\":0.25},"
                + "{\"ingredientId\":" + saltId + ",\"quantity\":0.005}]");

        var chickenComponentId = createComponent("Chicken");
        putRecipe(chickenComponentId, 10, "[{\"ingredientId\":" + chickenId + ",\"quantity\":2.5},"
                + "{\"ingredientId\":" + oilId + ",\"quantity\":0.1},"
                + "{\"ingredientId\":" + saltId + ",\"quantity\":0.01}]");

        var riceMealId = createMealCatalog("Rice Meal", riceComponentId);
        var chickenMealId = createMealCatalog("Chicken Meal", chickenComponentId);

        createDailyOption(riceMealId, 20);
        createDailyOption(chickenMealId, 10);
        createDailyComponentStock(riceComponentId, 10);

        return new Fixture(riceId, chickenId, oilId, saltId);
    }

    @Test
    void getRequirements_consolidatesAcrossOptionsAndComponentStock_sharingIngredientsViaDifferentComponents() throws Exception {
        var fixture = setUpRiceAndChickenPlan();

        // rice: option(1.5/10*20=3.0) + componentStock(1.5/10*10=1.5) = 4.5
        // chicken: option(2.5/10*10=2.5) = 2.5
        // oil: riceOption(.25/10*20=0.5) + chickenOption(.1/10*10=0.1) + riceStock(.25/10*10=0.25) = 0.85
        // salt: riceOption(.005/10*20=0.01) + chickenOption(.01/10*10=0.01) + riceStock(.005/10*10=0.005) = 0.025
        mockMvc.perform(get("/admin/daily-planning/" + PLANNING_DATE + "/ingredient-requirements")
                        .param("period", "Lunch").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirements[?(@.ingredientId == " + fixture.riceId() + ")].calculatedQuantity").value(4.5))
                .andExpect(jsonPath("$.requirements[?(@.ingredientId == " + fixture.chickenId() + ")].calculatedQuantity").value(2.5))
                .andExpect(jsonPath("$.requirements[?(@.ingredientId == " + fixture.oilId() + ")].calculatedQuantity").value(0.85))
                .andExpect(jsonPath("$.requirements[?(@.ingredientId == " + fixture.saltId() + ")].calculatedQuantity").value(0.025));
    }

    @Test
    void confirm_usesEditedQuantity_deductsStock_andExcludesReviewedRowsFromNextFetch() throws Exception {
        var riceId = createIngredient("Rice", "KG");
        var oilId = createIngredient("Cooking Oil", "LITRE");
        createGrv(riceId, "10.0000");
        createGrv(oilId, "10.0000");

        var riceComponentId = createComponent("Rice");
        putRecipe(riceComponentId, 10, "[{\"ingredientId\":" + riceId + ",\"quantity\":1.5},"
                + "{\"ingredientId\":" + oilId + ",\"quantity\":0.25}]");
        var riceMealId = createMealCatalog("Rice Meal", riceComponentId);
        createDailyOption(riceMealId, 20); // calculated: rice=3.0, oil=0.5

        // Edit rice's calculated 3.0 down to 2.0 before confirming; leave oil at its calculated value.
        var confirmBody = "{\"period\":\"Lunch\",\"adjustments\":["
                + "{\"ingredientId\":" + riceId + ",\"finalQuantity\":2.0},"
                + "{\"ingredientId\":" + oilId + ",\"finalQuantity\":0.5}]}";
        mockMvc.perform(post("/admin/daily-planning/" + PLANNING_DATE + "/confirm-ingredient-requirements")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortfalls.length()").value(0));

        // Deduction used the edited 2.0, not the calculated 3.0.
        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.currentStock").value(8.0));
        mockMvc.perform(get("/admin/ingredients/" + oilId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.currentStock").value(9.5));

        // The now-reviewed option is excluded from a re-fetch.
        mockMvc.perform(get("/admin/daily-planning/" + PLANNING_DATE + "/ingredient-requirements")
                        .param("period", "Lunch").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.requirements.length()").value(0));

        // Adding a new option and re-fetching surfaces only its own ingredients.
        createDailyOption(riceMealId, 10); // calculated: rice=1.5, oil=0.25
        mockMvc.perform(get("/admin/daily-planning/" + PLANNING_DATE + "/ingredient-requirements")
                        .param("period", "Lunch").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.requirements[?(@.ingredientId == " + riceId + ")].calculatedQuantity").value(1.5))
                .andExpect(jsonPath("$.requirements[?(@.ingredientId == " + oilId + ")].calculatedQuantity").value(0.25));
    }

    @Test
    void confirm_withInsufficientStock_returnsShortfalls_butStillDeducts() throws Exception {
        var riceId = createIngredient("Rice", "KG");
        createGrv(riceId, "1.0000"); // far less than what will be requested

        var riceComponentId = createComponent("Rice");
        putRecipe(riceComponentId, 10, "[{\"ingredientId\":" + riceId + ",\"quantity\":1.5}]");
        var riceMealId = createMealCatalog("Rice Meal", riceComponentId);
        createDailyOption(riceMealId, 20); // calculated rice = 3.0

        var confirmBody = "{\"period\":\"Lunch\",\"adjustments\":[{\"ingredientId\":" + riceId + ",\"finalQuantity\":3.0}]}";
        mockMvc.perform(post("/admin/daily-planning/" + PLANNING_DATE + "/confirm-ingredient-requirements")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortfalls.length()").value(1))
                .andExpect(jsonPath("$.shortfalls[0].ingredientId").value(riceId))
                .andExpect(jsonPath("$.shortfalls[0].resultingStock").value(-2.0));

        // Deduction proceeded despite the shortfall — negative stock allowed, no exception.
        mockMvc.perform(get("/admin/ingredients/" + riceId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.currentStock").value(-2.0));
    }

    @Test
    void componentWithNoRecipe_isExcludedFromRequirements() throws Exception {
        var componentId = createComponent("Bought-in Extra");
        var mealId = createMealCatalog("Extra Meal", componentId);
        createDailyOption(mealId, 15);

        mockMvc.perform(get("/admin/daily-planning/" + PLANNING_DATE + "/ingredient-requirements")
                        .param("period", "Lunch").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirements.length()").value(0));
    }
}
