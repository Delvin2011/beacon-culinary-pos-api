package com.beaconculinary.api.menu;

import com.beaconculinary.api.inventory.RecipeRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the Stage 1.2 catalog + daily-planning endpoints against a real SQL Server database. */
@SpringBootTest
@AutoConfigureMockMvc
class MenuIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MealCatalogRepository mealCatalogRepository;
    @Autowired
    private ComponentCatalogRepository componentCatalogRepository;
    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired
    private DailyComponentStockRepository dailyComponentStockRepository;

    private String adminToken;
    private String cashierToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
    }

    @AfterEach
    void tearDown() {
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
    }

    private long createComponent(String name, String extraPrice) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"extraPrice\":" + extraPrice + "}";
        var response = mockMvc.perform(post("/admin/component-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long createMealCatalog(String name, String price, long... componentIds) throws Exception {
        var idsJson = new StringBuilder("[");
        for (int i = 0; i < componentIds.length; i++) {
            if (i > 0) idsJson.append(",");
            idsJson.append(componentIds[i]);
        }
        idsJson.append("]");
        var body = "{\"name\":\"" + name + "\",\"description\":\"desc\",\"price\":" + price + ",\"componentIds\":" + idsJson + "}";
        var response = mockMvc.perform(post("/admin/meal-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long lunchPeriodId() throws Exception {
        var response = mockMvc.perform(get("/meal-periods")).andReturn().getResponse().getContentAsString();
        for (var node : MAPPER.readTree(response)) {
            if (node.get("name").asText().equalsIgnoreCase("lunch")) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException("Lunch period not seeded");
    }

    @Test
    void createMealCatalogWithComponents_returnsNestedComponentsInResponse() throws Exception {
        var chickenId = createComponent("Chicken", "12.00");
        var riceId = createComponent("Rice", "8.00");

        var body = "{\"name\":\"Rice & Chicken\",\"description\":\"desc\",\"price\":45.00,\"componentIds\":[" + chickenId + "," + riceId + "]}";

        mockMvc.perform(post("/admin/meal-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Rice & Chicken"))
                .andExpect(jsonPath("$.components.length()").value(2));
    }

    @Test
    void createDailyOption_snapshotsNameDescriptionPriceFromCatalog() throws Exception {
        var mealCatalogId = createMealCatalog("Potatoes & Beef", "50.00");
        var lunchId = lunchPeriodId();
        var today = LocalDate.now().toString();

        var body = "{\"mealPeriodId\":" + lunchId + ",\"optionDate\":\"" + today + "\",\"mealCatalogId\":" + mealCatalogId + ",\"plannedPortions\":30}";

        mockMvc.perform(post("/admin/daily-options").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Potatoes & Beef"))
                .andExpect(jsonPath("$.price").value(50.00))
                .andExpect(jsonPath("$.plannedPortions").value(30))
                .andExpect(jsonPath("$.portionsRemaining").value(30));
    }

    @Test
    void updatingCatalogPriceAfterDailyOptionExists_doesNotChangeSnapshottedPrice() throws Exception {
        var mealCatalogId = createMealCatalog("Veg Thali", "40.00");
        var lunchId = lunchPeriodId();
        var today = LocalDate.now().toString();

        var createBody = "{\"mealPeriodId\":" + lunchId + ",\"optionDate\":\"" + today + "\",\"mealCatalogId\":" + mealCatalogId + ",\"plannedPortions\":10}";
        var response = mockMvc.perform(post("/admin/daily-options").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var optionId = MAPPER.readTree(response).get("id").asLong();

        var updateBody = "{\"name\":\"Veg Thali\",\"description\":\"desc\",\"price\":999.00,\"active\":true,\"componentIds\":[]}";
        mockMvc.perform(put("/admin/meal-catalog/" + mealCatalogId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk());

        assertThat(dailyMealOptionRepository.findById(optionId).orElseThrow().getPrice())
                .isEqualByComparingTo(new BigDecimal("40.00"));
    }

    @Test
    void createDailyComponentStock_independentOfAnyOption() throws Exception {
        var chickenId = createComponent("Chicken", "12.00");
        var lunchId = lunchPeriodId();
        var today = LocalDate.now().toString();

        var body = "{\"componentCatalogId\":" + chickenId + ",\"mealPeriodId\":" + lunchId + ",\"optionDate\":\"" + today + "\",\"bufferQuantity\":40}";

        mockMvc.perform(post("/admin/daily-component-stock").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bufferQuantity").value(40))
                .andExpect(jsonPath("$.bufferRemaining").value(40))
                .andExpect(jsonPath("$.componentName").value("Chicken"));
    }

    @Test
    void getTodayMenu_extrasIncludeComponentNotInAnyTodayOptionComposition() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var chickenId = createComponent("Chicken", "12.00"); // not part of any option's composition
        var mealCatalogId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var lunchId = lunchPeriodId();
        var today = LocalDate.now().toString();

        var optionBody = "{\"mealPeriodId\":" + lunchId + ",\"optionDate\":\"" + today + "\",\"mealCatalogId\":" + mealCatalogId + ",\"plannedPortions\":20}";
        mockMvc.perform(post("/admin/daily-options").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(optionBody))
                .andExpect(status().isCreated());

        var stockBody = "{\"componentCatalogId\":" + chickenId + ",\"mealPeriodId\":" + lunchId + ",\"optionDate\":\"" + today + "\",\"bufferQuantity\":10}";
        mockMvc.perform(post("/admin/daily-component-stock").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(stockBody))
                .andExpect(status().isCreated());

        // Note: length()/index-based assertions are deliberately avoided here — a seeded
        // dev database (V27) may already have its own "today" lunch options/extras
        // alongside whatever this test creates, so we only assert presence.
        mockMvc.perform(get("/menu/today").param("period", "LUNCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[?(@.name=='Potatoes & Beef')]").exists())
                .andExpect(jsonPath("$.availableExtras[?(@.componentName == 'Chicken')]").exists());
    }

    @Test
    void cashierAttemptingAdminCatalogEndpoint_returns403() throws Exception {
        var body = "{\"name\":\"Rice\",\"extraPrice\":8.00}";
        mockMvc.perform(post("/admin/component-catalog").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void mealPeriods_publicEndpoint_returnsBreakfastLunchAndAllDay() throws Exception {
        mockMvc.perform(get("/meal-periods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='Breakfast')]").exists())
                .andExpect(jsonPath("$[?(@.name=='Lunch')]").exists())
                .andExpect(jsonPath("$[?(@.name=='All Day' && @.allDay==true)]").exists());
    }
}
