package com.beaconculinary.api.menu;

import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.CatalogTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.DailyPlanTestHelper;
import com.beaconculinary.api.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Part A: the catalogue tree, light meals (always-on, unlimited, "Out today"), the period rules
 * and order creation for always-on items. Data from the work order: Pap & Stew (Set meals, R35),
 * Chicken Burger (R55) and Beef Burger (R60) under Burgers, Chicken Wrap (R45) under Wraps. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class CatalogCategoriesIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired
    private ShiftRepository shiftRepository;
    @Autowired
    private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired
    private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired
    private MealCatalogRepository mealCatalogRepository;
    @Autowired
    private CatalogCategoryRepository categoryRepository;
    @Autowired
    private MutableClock clock;

    private final List<Long> createdCategories = new ArrayList<>();
    private String adminToken;
    private String cashierToken;
    private String headChefToken;
    private long lunchId;
    private long allDayId;
    private long burgersId;
    private long wrapsId;
    private long papStew;
    private long chickenBurger;
    private long beefBurger;
    private long chickenWrap;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside Lunch (12:00-14:30)
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        headChefToken = AuthTestHelper.loginAsHeadChef(mockMvc);
        lunchId = periodId("Lunch");
        allDayId = periodId("All Day");
        burgersId = CatalogTestHelper.categoryId(mockMvc, "Light meals > Burgers");
        wrapsId = CatalogTestHelper.categoryId(mockMvc, "Light meals > Wraps");

        papStew = createMeal("Pap & Stew", "35.00", CatalogTestHelper.setMealsId(mockMvc));
        chickenBurger = createMeal("Chicken Burger", "55.00", burgersId);
        beefBurger = createMeal("Beef Burger", "60.00", burgersId);
        chickenWrap = createMeal("Chicken Wrap", "45.00", wrapsId);
    }

    @AfterEach
    void tearDown() {
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        categoryRepository.deleteAllById(createdCategories);
        shiftRepository.deleteAll();
    }

    // --- helpers -------------------------------------------------------------------------------

    private ResultActions as(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : MAPPER.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    private static long id(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private long periodId(String name) throws Exception {
        for (var node : MAPPER.readTree(mockMvc.perform(get("/meal-periods")).andReturn().getResponse().getContentAsString())) {
            if (node.get("name").asText().equalsIgnoreCase(name)) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException(name + " period not seeded");
    }

    private record MealReq(String name, String description, String price, long categoryId, boolean active) {
    }

    private long createMeal(String name, String price, long categoryId) throws Exception {
        return id(as(adminToken, post("/admin/meal-catalog"), new MealReq(name, "desc", price, categoryId, true))
                .andExpect(status().isCreated()));
    }

    private ResultActions updateMeal(long id, String name, String price, long categoryId) throws Exception {
        return as(adminToken, put("/admin/meal-catalog/" + id), new MealReq(name, "desc", price, categoryId, true));
    }

    private record PlanReq(long mealPeriodId, String optionDate, long mealCatalogId, int plannedPortions) {
    }

    private ResultActions plan(long periodId, long mealId, int portions) throws Exception {
        return as(adminToken, post("/admin/daily-options"), new PlanReq(periodId, LocalDate.now(clock).toString(), mealId, portions));
    }

    private JsonNode tillMenu() throws Exception {
        return MAPPER.readTree(mockMvc.perform(get("/menu/today")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("options");
    }

    private JsonNode menuItem(String name) throws Exception {
        for (var option : tillMenu()) {
            if (option.get("name").asText().equals(name)) {
                return option;
            }
        }
        throw new AssertionError(name + " is not on the till menu");
    }

    private long alwaysOnOptionsToday() {
        var mine = List.of(chickenBurger, beefBurger, chickenWrap, papStew);
        return dailyMealOptionRepository.findAlwaysOnMealIds(LocalDate.now(clock)).stream().filter(mine::contains).count();
    }

    private void openShift() throws Exception {
        as(cashierToken, post("/shifts/open"), "{\"openingFloat\":500.00}").andExpect(status().isCreated());
    }

    private record Extra(long dailyComponentStockId, int quantity) {
    }

    private record Line(long dailyMealOptionId, int quantity, List<Extra> extras) {
        Line(long dailyMealOptionId, int quantity) {
            this(dailyMealOptionId, quantity, List.of());
        }
    }

    private ResultActions order(String total, Line... lines) throws Exception {
        var payment = Map.of("method", "CASH", "amount", total, "amountTendered", total);
        return as(cashierToken, post("/orders"), Map.of("payments", List.of(payment), "lines", List.of(lines)));
    }

    private long plannedReadyOption(long mealId, long periodId, int portions) throws Exception {
        var optionId = id(plan(periodId, mealId, portions).andExpect(status().isCreated()));
        DailyPlanTestHelper.markReady(dailyMealOptionRepository, optionId);
        return optionId;
    }

    // --- A11 ----------------------------------------------------------------------------------

    @Test
    void firstTillMenuRequest_createsTheAlwaysOnOptions_once() throws Exception {
        assertThat(alwaysOnOptionsToday()).isZero();

        var burger = menuItem("Chicken Burger");
        assertThat(alwaysOnOptionsToday()).isEqualTo(3);
        assertThat(burger.get("group").asText()).isEqualTo("LIGHT_MEALS");
        assertThat(burger.get("category").get("name").asText()).isEqualTo("Burgers");
        assertThat(burger.get("period").get("id").asLong()).isEqualTo(allDayId);
        assertThat(burger.get("portionsRemaining").isNull()).isTrue();
        assertThat(burger.get("plannedPortions").isNull()).isTrue();
        assertThat(burger.get("outToday").asBoolean()).isFalse();
        assertThat(burger.get("status").asText()).isEqualTo("READY");

        tillMenu();
        assertThat(alwaysOnOptionsToday()).isEqualTo(3);
    }

    @Test
    void periodRules_forPlanning() throws Exception {
        plan(lunchId, chickenBurger, 10).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALWAYS_ON_ITEM_NOT_PLANNABLE"));
        plan(allDayId, papStew, 10).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEAL_NOT_ALLOWED_IN_PERIOD"));
        plan(lunchId, papStew, 10).andExpect(status().isCreated());

        mockMvc.perform(get("/meal-periods")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'All Day')].plannable").value(false))
                .andExpect(jsonPath("$[?(@.name == 'All Day')].accepts").value("LIGHT_MEALS"))
                .andExpect(jsonPath("$[?(@.name == 'Lunch')].plannable").value(true));
        as(adminToken, get("/admin/daily-planning/" + LocalDate.now(clock)).param("period", "All Day"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.plannable").value(false));

        // The planning picker lists only the meals a period accepts.
        as(adminToken, get("/admin/meal-catalog").param("periodId", String.valueOf(lunchId)), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Pap & Stew")))
                .andExpect(jsonPath("$[*].name", not(hasItem("Chicken Burger"))));
        as(adminToken, get("/admin/meal-catalog").param("periodId", String.valueOf(allDayId)), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", not(hasItem("Chicken Burger"))))
                .andExpect(jsonPath("$[*].name", not(hasItem("Pap & Stew"))));
    }

    @Test
    void outToday_blocksOrders_showsOnTheMenu_andClearsItselfNextDay() throws Exception {
        var beef = menuItem("Beef Burger").get("id").asLong();
        openShift();

        as(adminToken, post("/admin/meal-catalog/" + beefBurger + "/out-today"), Map.of("out", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outToday").value(true));
        assertThat(menuItem("Beef Burger").get("outToday").asBoolean()).isTrue();
        order("60.00", new Line(beef, 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_UNAVAILABLE"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Beef Burger")));

        clock.setInstant(clock.instant().plus(Duration.ofDays(1)));
        assertThat(menuItem("Beef Burger").get("outToday").asBoolean()).isFalse();
        as(adminToken, get("/admin/meal-catalog").param("categoryId", String.valueOf(burgersId)), null)
                .andExpect(jsonPath("$[?(@.name == 'Beef Burger')].outToday").value(false));
    }

    @Test
    void outToday_permissions() throws Exception {
        as(headChefToken, post("/admin/meal-catalog/" + chickenWrap + "/out-today"), Map.of("out", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outToday").value(true));
        as(headChefToken, post("/admin/meal-catalog/" + chickenWrap + "/out-today"), Map.of("out", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outToday").value(false));
        as(adminToken, post("/admin/meal-catalog/" + papStew + "/out-today"), Map.of("out", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OUT_TODAY_NOT_APPLICABLE"));
        as(cashierToken, post("/admin/meal-catalog/" + chickenWrap + "/out-today"), Map.of("out", true))
                .andExpect(status().isForbidden());
    }

    @Test
    void priceChange_movesTodaysOption_butNotPlacedOrders() throws Exception {
        var burger = menuItem("Chicken Burger").get("id").asLong();
        openShift();
        var orderId = id(order("55.00", new Line(burger, 1)).andExpect(status().isCreated()));

        updateMeal(chickenBurger, "Chicken Burger", "58.00", burgersId).andExpect(status().isOk());

        var option = menuItem("Chicken Burger");
        assertThat(option.get("id").asLong()).isEqualTo(burger);
        assertThat(option.get("price").decimalValue()).isEqualByComparingTo("58.00");
        as(cashierToken, get("/orders/" + orderId), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice").value(55.00));
    }

    @Test
    void mixedOrder_decrementsOnlyThePlannedOption() throws Exception {
        var stewOption = plannedReadyOption(papStew, lunchId, 10);
        var burger = menuItem("Chicken Burger").get("id").asLong();
        openShift();

        order("145.00", new Line(burger, 2), new Line(stewOption, 1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(145.00));

        assertThat(dailyMealOptionRepository.findById(stewOption).orElseThrow().getPortionsRemaining()).isEqualTo(9);
        var burgerOption = dailyMealOptionRepository.findById(burger).orElseThrow();
        assertThat(burgerOption.getPortionsRemaining()).isNull();
        assertThat(burgerOption.getPlannedPortions()).isNull();
    }

    @Test
    void extraOnALightMeal_isRefused() throws Exception {
        var burger = menuItem("Chicken Burger").get("id").asLong();
        openShift();

        order("65.00", new Line(burger, 1, List.of(new Extra(999999L, 1)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXTRAS_NOT_ALLOWED"));
    }

    @Test
    void movingAPlannedMealToAnotherGroup_isRefused() throws Exception {
        plan(lunchId, papStew, 10).andExpect(status().isCreated());

        updateMeal(papStew, "Pap & Stew", "35.00", burgersId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEAL_HAS_PLANNED_OPTIONS"));
        // Staying in the group (only a price change) is fine.
        updateMeal(papStew, "Pap & Stew", "36.00", CatalogTestHelper.setMealsId(mockMvc)).andExpect(status().isOk());
    }

    @Test
    void deactivatingASubcategoryWithActiveItems_isRefused() throws Exception {
        as(adminToken, put("/admin/catalog/categories/" + wrapsId), Map.of("name", "Wraps", "sortOrder", 2, "active", false))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_HAS_ITEMS"));
    }

    // --- category endpoints --------------------------------------------------------------------

    @Test
    void categoryTree_andItsRules() throws Exception {
        as(adminToken, get("/admin/catalog/categories"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Set meals')].assignable").value(true))
                .andExpect(jsonPath("$[?(@.name == 'Light meals')].assignable").value(false))
                .andExpect(jsonPath("$[?(@.name == 'Light meals')].itemCount").value(3))
                .andExpect(jsonPath("$[?(@.name == 'Light meals')].children[?(@.name == 'Burgers')].itemCount").value(2));
        as(headChefToken, get("/admin/catalog/categories"), null).andExpect(status().isOk());
        as(headChefToken, post("/admin/catalog/categories"), Map.of("parentId", burgersId, "name", "Nope"))
                .andExpect(status().isForbidden());

        var lightMeals = categoryRepository.findByParentIsNullAndGroup(CatalogGroup.LIGHT_MEALS).orElseThrow().getId();
        var setMeals = CatalogTestHelper.setMealsId(mockMvc);
        var pizzas = id(as(adminToken, post("/admin/catalog/categories"), Map.of("parentId", lightMeals, "name", "Test Pizzas", "sortOrder", 9))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.group").value("LIGHT_MEALS")));
        createdCategories.add(pizzas);

        as(adminToken, post("/admin/catalog/categories"), Map.of("parentId", lightMeals, "name", "test pizzas"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"));
        as(adminToken, post("/admin/catalog/categories"), Map.of("parentId", setMeals, "name", "Curries"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_INVALID"));
        as(adminToken, post("/admin/catalog/categories"), Map.of("parentId", burgersId, "name", "Deep"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_INVALID"));
        as(adminToken, put("/admin/catalog/categories/" + lightMeals), Map.of("name", "Snacks", "sortOrder", 2, "active", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_FIXED"));
        as(adminToken, put("/admin/catalog/categories/" + pizzas), Map.of("name", "Test Pizzas & Pies", "sortOrder", 5, "active", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        // A meal can't sit on a group row or in an inactive category.
        as(adminToken, post("/admin/meal-catalog"), new MealReq("Loose", "desc", "10.00", lightMeals, true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_INVALID"));
        as(adminToken, post("/admin/meal-catalog"), new MealReq("Margherita", "desc", "70.00", pizzas, true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_INVALID"));
        as(adminToken, post("/admin/meal-catalog"), "{\"name\":\"No Category\",\"price\":10.00}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.categoryId").value("categoryId is required"));
    }

    @Test
    void mealResponses_carryCategoryGroupAndAvailability() throws Exception {
        as(adminToken, get("/admin/meal-catalog").param("group", "LIGHT_MEALS"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Chicken Burger')].category.name").value("Burgers"))
                .andExpect(jsonPath("$[?(@.name == 'Chicken Burger')].availability").value("ALWAYS"))
                .andExpect(jsonPath("$[*].name", not(hasItem("Pap & Stew"))));
        as(adminToken, get("/admin/meal-catalog").param("group", "SET_MEALS"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Pap & Stew')].availability").value("PLANNED"))
                .andExpect(jsonPath("$[?(@.name == 'Pap & Stew')].category.name").value("Set meals"));
    }

    @Test
    void deactivatedLightMeal_leavesTheMenu_andCantBeSold() throws Exception {
        var wrap = menuItem("Chicken Wrap").get("id").asLong();
        openShift();
        as(adminToken, put("/admin/meal-catalog/" + chickenWrap),
                Map.of("name", "Chicken Wrap", "price", "45.00", "categoryId", wrapsId, "active", false))
                .andExpect(status().isOk());

        assertThat(tillMenu().findValues("name").stream().map(JsonNode::asText)).doesNotContain("Chicken Wrap");
        order("45.00", new Line(wrap, 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ITEM_UNAVAILABLE"));
    }
}
