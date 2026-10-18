package com.beaconculinary.api.menu;

import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.inventory.StockRequestRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Editing and removing planned meals and extras: allowed only while PLANNED (before the
 * ingredient review), refused once reviewed or on sale. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class DailyPlanEditIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StockRequestRepository stockRequestRepository;
    @Autowired
    private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired
    private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired
    private MealCatalogRepository mealCatalogRepository;
    @Autowired
    private ComponentCatalogRepository componentCatalogRepository;
    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private MutableClock clock;

    private String adminToken;
    private long lunchId;
    private long breakfastId;
    private String today;
    private String tomorrow;
    private final List<Long> stockRequestIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        lunchId = periodId("lunch");
        breakfastId = periodId("breakfast");
        today = LocalDate.now(clock).toString();
        tomorrow = LocalDate.now(clock).plusDays(1).toString();
    }

    @AfterEach
    void tearDown() {
        stockRequestIds.forEach(stockRequestRepository::deleteById);
        stockRequestIds.clear();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
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

    private ResultActions as(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body)
            throws Exception {
        request.header("Authorization", "Bearer " + adminToken);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private long created(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asLong();
    }

    private long component(String name, String extraPrice) throws Exception {
        return created(as(post("/admin/component-catalog"), "{\"name\":\"" + name + "\",\"extraPrice\":" + extraPrice + "}"));
    }

    private long meal(String name, String price) throws Exception {
        return created(as(post("/admin/meal-catalog"), "{\"name\":\"" + name + "\",\"description\":\"" + name
                + " desc\",\"price\":" + price + "}"));
    }

    private static String optionBody(long periodId, String date, long mealId, int portions) {
        return "{\"mealPeriodId\":" + periodId + ",\"optionDate\":\"" + date + "\",\"mealCatalogId\":" + mealId
                + ",\"plannedPortions\":" + portions + "}";
    }

    private static String stockBody(long componentId, long periodId, String date, int buffer) {
        return "{\"componentCatalogId\":" + componentId + ",\"mealPeriodId\":" + periodId + ",\"optionDate\":\"" + date
                + "\",\"bufferQuantity\":" + buffer + "}";
    }

    private void confirmIngredients() throws Exception {
        var response = as(post("/admin/daily-planning/" + today + "/confirm-ingredient-requirements"),
                "{\"period\":\"LUNCH\",\"adjustments\":[]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        stockRequestIds.add(MAPPER.readTree(response).get("stockRequestId").asLong());
    }

    @Test
    void editPlannedMeal_portionsOnlyKeepsTheSnapshot_newMealTakesItsOwn() throws Exception {
        var stew = meal("Lamb Stew", "50.00");
        var optionId = created(as(post("/admin/daily-options"), optionBody(lunchId, today, stew, 20)));

        // A later catalog price change must not reach the planned day, even through an edit that keeps the meal.
        as(put("/admin/meal-catalog/" + stew), "{\"name\":\"Lamb Stew\",\"description\":\"d\",\"price\":60.00,\"active\":true}")
                .andExpect(status().isOk());
        as(put("/admin/daily-options/" + optionId), optionBody(lunchId, today, stew, 30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedPortions").value(30))
                .andExpect(jsonPath("$.price").value(50.00))
                .andExpect(jsonPath("$.status").value("PLANNED"));

        // Switching the meal (and moving it to tomorrow's breakfast) snapshots the new meal.
        var pie = meal("Cottage Pie", "45.00");
        as(put("/admin/daily-options/" + optionId), optionBody(breakfastId, tomorrow, pie, 12))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(optionId))
                .andExpect(jsonPath("$.name").value("Cottage Pie"))
                .andExpect(jsonPath("$.description").value("Cottage Pie desc"))
                .andExpect(jsonPath("$.price").value(45.00))
                .andExpect(jsonPath("$.mealPeriodId").value(breakfastId))
                .andExpect(jsonPath("$.optionDate").value(tomorrow))
                .andExpect(jsonPath("$.plannedPortions").value(12));

        as(get("/admin/daily-planning/" + today).param("period", "Lunch"), null).andExpect(jsonPath("$.options").isEmpty());
        as(get("/admin/daily-planning/" + tomorrow).param("period", "Breakfast"), null)
                .andExpect(jsonPath("$.options[0].name").value("Cottage Pie"));
    }

    @Test
    void deletePlannedMealAndExtra_removesThemFromTheDay() throws Exception {
        var optionId = created(as(post("/admin/daily-options"), optionBody(lunchId, today, meal("Lamb Stew", "50.00"), 20)));
        var stockId = created(as(post("/admin/daily-component-stock"), stockBody(component("Side Salad", "10.00"), lunchId, today, 5)));

        as(delete("/admin/daily-options/" + optionId), null).andExpect(status().isNoContent());
        as(delete("/admin/daily-component-stock/" + stockId), null).andExpect(status().isNoContent());

        as(get("/admin/daily-planning/" + today).param("period", "Lunch"), null)
                .andExpect(jsonPath("$.options").isEmpty())
                .andExpect(jsonPath("$.componentStock").isEmpty());
        assertThat(dailyMealOptionRepository.existsById(optionId)).isFalse();

        as(delete("/admin/daily-options/" + optionId), null).andExpect(status().isNotFound());
        as(put("/admin/daily-component-stock/" + stockId), stockBody(1, lunchId, today, 5)).andExpect(status().isNotFound());
    }

    @Test
    void editPlannedExtra_bufferAndComponent() throws Exception {
        var salad = component("Side Salad", "10.00");
        var stockId = created(as(post("/admin/daily-component-stock"), stockBody(salad, lunchId, today, 5)));

        as(put("/admin/daily-component-stock/" + stockId), stockBody(salad, lunchId, today, 8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bufferQuantity").value(8))
                .andExpect(jsonPath("$.extraPrice").value(10.00));

        var gravy = component("Extra Gravy", "5.00");
        as(put("/admin/daily-component-stock/" + stockId), stockBody(gravy, lunchId, today, 8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.componentCatalogId").value(gravy))
                .andExpect(jsonPath("$.componentName").value("Extra Gravy"))
                .andExpect(jsonPath("$.extraPrice").value(5.00));
    }

    @Test
    void reviewedOrReadyItems_cantBeEditedOrRemoved() throws Exception {
        var mealId = meal("Lamb Stew", "50.00");
        var optionId = created(as(post("/admin/daily-options"), optionBody(lunchId, today, mealId, 20)));
        var saladId = component("Side Salad", "10.00");
        var stockId = created(as(post("/admin/daily-component-stock"), stockBody(saladId, lunchId, today, 5)));
        confirmIngredients();

        as(put("/admin/daily-options/" + optionId), optionBody(lunchId, today, mealId, 25))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("'Lamb Stew' can't be changed or removed: its ingredients have already been reviewed."));
        as(delete("/admin/daily-options/" + optionId), null).andExpect(status().isBadRequest());
        as(put("/admin/daily-component-stock/" + stockId), stockBody(saladId, lunchId, today, 9)).andExpect(status().isBadRequest());
        as(delete("/admin/daily-component-stock/" + stockId), null).andExpect(status().isBadRequest());

        as(put("/admin/daily-options/" + optionId + "/actual"), "{\"actualPortions\":18}").andExpect(status().isOk());
        as(delete("/admin/daily-options/" + optionId), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "'Lamb Stew' can't be changed or removed: its ingredients have already been reviewed and it is on sale."));
        assertThat(dailyMealOptionRepository.findById(optionId).orElseThrow().getPlannedPortions()).isEqualTo(20);
    }

    @Test
    void invalidEdit_isRefused_andCashierCantEditOrDelete() throws Exception {
        var mealId = meal("Lamb Stew", "50.00");
        var optionId = created(as(post("/admin/daily-options"), optionBody(lunchId, today, mealId, 20)));

        as(put("/admin/daily-options/" + optionId), optionBody(lunchId, today, 999999, 20))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("mealCatalogId does not exist."));
        as(put("/admin/daily-options/" + optionId), optionBody(lunchId, today, mealId, -1)).andExpect(status().isBadRequest());

        var cashier = AuthTestHelper.loginAsCashier(mockMvc);
        mockMvc.perform(delete("/admin/daily-options/" + optionId).header("Authorization", "Bearer " + cashier))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/daily-options/" + optionId).header("Authorization", "Bearer " + cashier)
                        .contentType(MediaType.APPLICATION_JSON).content(optionBody(lunchId, today, mealId, 5)))
                .andExpect(status().isForbidden());
        assertThat(dailyMealOptionRepository.findById(optionId).orElseThrow().getPlannedPortions()).isEqualTo(20);
    }
}
