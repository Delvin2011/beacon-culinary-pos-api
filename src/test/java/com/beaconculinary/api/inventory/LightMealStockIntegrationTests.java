package com.beaconculinary.api.inventory;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.CatalogTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.PosFlowHelper.ComplimentaryReq;
import com.beaconculinary.api.support.PosFlowHelper.LineReq;
import com.beaconculinary.api.users.UserRepository;
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

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Light meal stock: the review, deduct on sale, restore on void. Data from the work order: Chicken
 * Burger (Burgers, R55), one component whose recipe has batch size 10 — patties 10 each, buns 10
 * each, lettuce 0.2 kg, sauce 0.3 L — so one unit is 1 / 1 / 0.02 / 0.03. Kitchen: 14 / 20 / 1.0 /
 * 2.0; Main Store: 120 / 150 / 5.0 / 10.0. Pap & Stew is a planned set meal with its own recipe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class LightMealStockIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired
    private AuthorizationTokenRepository authorizationTokenRepository;
    @Autowired
    private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired
    private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired
    private MealCatalogRepository mealCatalogRepository;
    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private ComponentCatalogRepository componentCatalogRepository;
    @Autowired
    private IngredientStockMovementRepository movementRepository;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ShiftRepository shiftRepository;

    private PosFlowHelper pos;
    private String adminToken;
    private String cashierToken;
    private long patties;
    private long buns;
    private long lettuce;
    private long sauce;
    private long chickenBurger;
    private long papStew;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside Lunch, so Pap & Stew can sell
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);

        patties = createIngredient("Chicken patties", "EACH");
        buns = createIngredient("Burger buns", "EACH");
        lettuce = createIngredient("Lettuce", "KG");
        sauce = createIngredient("Sauce", "LITRE");
        var burgerBuild = pos.createComponent("Chicken burger build", "0.00");
        putRecipe(burgerBuild, 10, patties, "10", buns, "10", lettuce, "0.2", sauce, "0.3");
        chickenBurger = createMeal("Chicken Burger", "55.00",
                CatalogTestHelper.categoryId(mockMvc, "Light meals > Burgers"), burgerBuild);

        stock("Kitchen", patties, "14");
        stock("Kitchen", buns, "20");
        stock("Kitchen", lettuce, "1.0");
        stock("Kitchen", sauce, "2.0");
        stock("Main Store", patties, "120");
        stock("Main Store", buns, "150");
        stock("Main Store", lettuce, "5.0");
        stock("Main Store", sauce, "10.0");

        var stew = pos.createComponent("Stew", "0.00");
        putRecipe(stew, 10, sauce, "1.0");
        papStew = pos.createMealCatalog("Pap & Stew", "35.00", stew);

        pos.openShift(cashierToken, "500.00");
    }

    @AfterEach
    void tearDown() {
        orderAdjustmentRepository.deleteAll();
        authorizationTokenRepository.deleteAll();
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        movementRepository.deleteAll();
        ingredientRepository.deleteAll();
        shiftRepository.deleteAll();
    }

    // --- helpers -------------------------------------------------------------------------------

    private ResultActions as(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    private long createIngredient(String name, String unit) throws Exception {
        var categories = PosFlowHelper.json(as(adminToken, get("/count-sheet-categories"), null));
        long drystock = 0;
        for (var node : categories) {
            if (node.get("name").asText().equalsIgnoreCase("DRYSTOCK")) {
                drystock = node.get("id").asLong();
            }
        }
        var body = Map.of("name", name, "unit", unit, "countSheetCategoryId", drystock);
        return PosFlowHelper.json(as(adminToken, post("/admin/ingredients"), body).andExpect(status().isCreated())).get("id").asLong();
    }

    /** Lines as ingredientId, quantity pairs, in order. */
    private void putRecipe(long componentId, int batchSize, Object... pairs) throws Exception {
        var lines = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < pairs.length; i += 2) {
            lines.add(Map.of("ingredientId", pairs[i], "quantity", new BigDecimal((String) pairs[i + 1])));
        }
        var body = Map.of("batchSize", batchSize, "lines", lines);
        as(adminToken, put("/admin/components/" + componentId + "/recipe"), body).andExpect(status().isOk());
    }

    private long createMeal(String name, String price, long categoryId, Long componentId) throws Exception {
        var body = Map.of("name", name, "description", "desc", "price", new BigDecimal(price), "categoryId", categoryId,
                "componentIds", componentId == null ? List.of() : List.of(componentId));
        return PosFlowHelper.json(as(adminToken, post("/admin/meal-catalog"), body).andExpect(status().isCreated())).get("id").asLong();
    }

    /** Opening stock, as an approved issue or a stock take would leave it. */
    private void stock(String location, long ingredientId, String quantity) throws Exception {
        var movement = new IngredientStockMovement();
        movement.setIngredient(ingredientRepository.findById(ingredientId).orElseThrow());
        movement.setMovementType(MovementType.RECEIVED);
        movement.setQuantity(new BigDecimal(quantity));
        movement.setLocation(locationRepository.findByNameIgnoreCase(location).orElseThrow());
        movement.setSourceType(MovementSourceType.STOCK_TAKE);
        movement.setSourceId(0L);
        movement.setRecordedBy(userRepository.findById(AuthTestHelper.adminId(mockMvc)).orElseThrow());
        movementRepository.save(movement);
    }

    private BigDecimal kitchen(long ingredientId) {
        var kitchenId = locationRepository.findByNameIgnoreCase("Kitchen").orElseThrow().getId();
        return movementRepository.sumQuantityByIngredientIdAndLocationId(ingredientId, kitchenId);
    }

    private JsonNode review(long mealId) throws Exception {
        return PosFlowHelper.json(as(adminToken, get("/admin/meal-catalog/" + mealId + "/stock-review"), null)
                .andExpect(status().isOk()));
    }

    private JsonNode menuItem(String name) throws Exception {
        for (var option : PosFlowHelper.json(mockMvc.perform(get("/menu/today"))).get("options")) {
            if (option.get("name").asText().equals(name)) {
                return option;
            }
        }
        throw new AssertionError(name + " is not on the till menu");
    }

    private long burgerOption() throws Exception {
        return menuItem("Chicken Burger").get("id").asLong();
    }

    private long sell(int burgers) throws Exception {
        var total = new BigDecimal("55.00").multiply(BigDecimal.valueOf(burgers)).toPlainString();
        return pos.placeCashOrder(cashierToken, total, new LineReq(burgerOption(), burgers, List.of()));
    }

    private List<IngredientStockMovement> lineMovements(MovementType type) {
        return movementRepository.findAll().stream()
                .filter(m -> m.getSourceType() == MovementSourceType.ORDER_LINE && m.getMovementType() == type)
                .toList();
    }

    private static void assertQuantity(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    // --- 1. Review ------------------------------------------------------------------------------

    @Test
    void review_matchesTheWorkedExample() throws Exception {
        var review = review(chickenBurger);
        assertThat(review.get("tracked").asBoolean()).isTrue();
        assertThat(review.get("status").asText()).isEqualTo("OK");
        assertThat(review.get("unitsAvailable").asInt()).isEqualTo(14);
        assertThat(review.get("limitingIngredient").asText()).isEqualTo("Chicken patties");
        assertThat(review.get("lowStockUnits").asInt()).isEqualTo(10);
        assertThat(review.get("outToday").asBoolean()).isFalse();

        var units = new java.util.HashMap<String, Integer>();
        for (var line : review.get("lines")) {
            units.put(line.get("name").asText(), line.get("unitsPossible").asInt());
        }
        assertThat(units).containsExactlyInAnyOrderEntriesOf(
                Map.of("Chicken patties", 14, "Burger buns", 20, "Lettuce", 50, "Sauce", 66));
        var patty = review.get("lines").get(0);
        assertThat(patty.get("uom").asText()).isEqualTo("EACH");
        assertQuantity(patty.get("perUnit").decimalValue(), "1");
        assertQuantity(patty.get("kitchenAvailable").decimalValue(), "14");
        assertQuantity(patty.get("mainStoreAvailable").decimalValue(), "120");
    }

    @Test
    void review_ofASetMeal_is400() throws Exception {
        as(adminToken, get("/admin/meal-catalog/" + papStew + "/stock-review"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STOCK_REVIEW_NOT_APPLICABLE"));
    }

    @Test
    void review_isOpenToTheHeadChef_notTheCashier() throws Exception {
        as(AuthTestHelper.loginAsHeadChef(mockMvc), get("/admin/meal-catalog/" + chickenBurger + "/stock-review"), null)
                .andExpect(status().isOk());
        as(cashierToken, get("/admin/meal-catalog/" + chickenBurger + "/stock-review"), null)
                .andExpect(status().isForbidden());
    }

    // --- 2, 3. Sell, then void -------------------------------------------------------------------

    @Test
    void sale_deductsAtTheKitchen_andVoidRestoresExactlyOnce() throws Exception {
        var orderId = sell(3);

        var consumed = lineMovements(MovementType.CONSUMED);
        assertThat(consumed).hasSize(4);
        var kitchenId = locationRepository.findByNameIgnoreCase("Kitchen").orElseThrow().getId();
        assertThat(consumed).allSatisfy(m -> assertThat(m.getLocation().getId()).isEqualTo(kitchenId));
        assertQuantity(kitchen(patties), "11");
        assertQuantity(kitchen(buns), "17");
        assertQuantity(kitchen(lettuce), "0.94");
        assertQuantity(kitchen(sauce), "1.91");
        var lettuceMove = consumed.stream().filter(m -> m.getIngredient().getId() == lettuce).findFirst().orElseThrow();
        assertQuantity(lettuceMove.getQuantity(), "-0.0600");

        pos.adjust(cashierToken, orderId, "WHOLE_ORDER"); // still PENDING: a void
        assertThat(lineMovements(MovementType.CONSUMPTION_REVERSED)).hasSize(4);
        assertQuantity(kitchen(patties), "14");
        assertQuantity(kitchen(buns), "20");
        assertQuantity(kitchen(lettuce), "1.0");
        assertQuantity(kitchen(sauce), "2.0");

        // A second void is refused, and nothing is reversed twice.
        pos.adjustWith(cashierToken, orderId, "WHOLE_ORDER", null, pos.authorizePin(cashierToken))
                .andExpect(status().is4xxClientError());
        assertThat(lineMovements(MovementType.CONSUMPTION_REVERSED)).hasSize(4);
    }

    // --- 4. Refund ------------------------------------------------------------------------------

    @Test
    void refund_givesNothingBack() throws Exception {
        var orderId = sell(2);
        pos.markDone(orderId);
        pos.adjust(cashierToken, orderId, "WHOLE_ORDER");

        assertThat(lineMovements(MovementType.CONSUMPTION_REVERSED)).isEmpty();
        assertQuantity(kitchen(patties), "12");
    }

    // --- 5. Low and Out, never blocked ----------------------------------------------------------

    @Test
    void lowThenOut_andSellingPastZeroIsAllowed() throws Exception {
        sell(6);
        assertThat(review(chickenBurger).get("status").asText()).isEqualTo("LOW");
        assertThat(menuItem("Chicken Burger").get("stockStatus").asText()).isEqualTo("LOW");
        assertThat(menuItem("Chicken Burger").get("unitsAvailable").asInt()).isEqualTo(8);

        sell(8);
        assertThat(review(chickenBurger).get("status").asText()).isEqualTo("OUT");

        sell(1);
        var review = review(chickenBurger);
        assertThat(review.get("status").asText()).isEqualTo("OUT");
        assertThat(review.get("unitsAvailable").asInt()).isZero();
        assertQuantity(review.get("lines").get(0).get("kitchenAvailable").decimalValue(), "-1");
        assertThat(review.get("lines").get(0).get("unitsPossible").asInt()).isZero();
    }

    @Test
    void lowStockUnits_isEditablePerMeal() throws Exception {
        var body = Map.of("name", "Chicken Burger", "description", "desc", "price", new BigDecimal("55.00"),
                "categoryId", CatalogTestHelper.categoryId(mockMvc, "Light meals > Burgers"),
                "componentIds", List.of(mealCatalogRepository.findWithComponentsById(chickenBurger).orElseThrow()
                        .getComponents().get(0).getComponentCatalog().getId()),
                "lowStockUnits", 20);
        as(adminToken, put("/admin/meal-catalog/" + chickenBurger), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowStockUnits").value(20))
                .andExpect(jsonPath("$.stockStatus").value("LOW"));
    }

    // --- 6. Complimentary ------------------------------------------------------------------------

    @Test
    void complimentary_deductsLikeASale() throws Exception {
        pos.submitComplimentary(cashierToken, ComplimentaryReq.staffMeal(pos.authorizePin(cashierToken)),
                        new LineReq(burgerOption(), 1, List.of()))
                .andExpect(status().isCreated());
        assertThat(lineMovements(MovementType.CONSUMED)).hasSize(4);
        assertQuantity(kitchen(patties), "13");
    }

    // --- 7. No recipe ---------------------------------------------------------------------------

    @Test
    void lightMealWithoutARecipe_isNotTracked() throws Exception {
        createMeal("Plain Chips", "20.00", CatalogTestHelper.categoryId(mockMvc, "Light meals > Chips & sides"), null);
        var chips = mealCatalogRepository.findAll().stream().filter(m -> m.getName().equals("Plain Chips")).findFirst().orElseThrow();

        var review = review(chips.getId());
        assertThat(review.get("tracked").asBoolean()).isFalse();
        assertThat(review.get("status").asText()).isEqualTo("NOT_TRACKED");
        assertThat(review.get("lines")).isEmpty();
        assertThat(menuItem("Plain Chips").get("stockStatus").asText()).isEqualTo("NOT_TRACKED");
        assertThat(menuItem("Plain Chips").get("unitsAvailable").isNull()).isTrue();

        pos.placeCashOrder(cashierToken, "20.00", new LineReq(menuItem("Plain Chips").get("id").asLong(), 1, List.of()));
        assertThat(lineMovements(MovementType.CONSUMED)).isEmpty();
    }

    // --- 8, 10. Planned meals, mixed orders ------------------------------------------------------

    @Test
    void plannedMeal_isNeverDeducted_andAMixedOrderDeductsOnlyTheBurger() throws Exception {
        var stewOption = pos.createDailyOption(pos.periodId("Lunch"), papStew, 10);
        pos.placeCashOrder(cashierToken, "35.00", new LineReq(stewOption, 1, List.of()));
        assertThat(lineMovements(MovementType.CONSUMED)).isEmpty();

        pos.placeCashOrder(cashierToken, "90.00", new LineReq(burgerOption(), 1, List.of()), new LineReq(stewOption, 1, List.of()));
        var consumed = lineMovements(MovementType.CONSUMED);
        assertThat(consumed).hasSize(4);
        assertThat(consumed.stream().map(IngredientStockMovement::getSourceId).distinct()).hasSize(1);
        assertQuantity(kitchen(sauce), "1.97"); // only the burger's 0.03
    }

    // --- List payload ---------------------------------------------------------------------------

    @Test
    void mealsList_carriesStockOnAlwaysAvailableRowsOnly() throws Exception {
        var meals = PosFlowHelper.json(as(adminToken, get("/admin/meal-catalog"), null).andExpect(status().isOk()));
        for (var meal : meals) {
            if (meal.get("id").asLong() == chickenBurger) {
                assertThat(meal.get("stockStatus").asText()).isEqualTo("OK");
                assertThat(meal.get("unitsAvailable").asInt()).isEqualTo(14);
            }
            if (meal.get("id").asLong() == papStew) {
                assertThat(meal.get("stockStatus").isNull()).isTrue();
            }
        }
        assertThat(menuItem("Chicken Burger").get("stockStatus").asText()).isEqualTo("OK");
    }
}
