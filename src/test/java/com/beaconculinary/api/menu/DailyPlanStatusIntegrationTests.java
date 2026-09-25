package com.beaconculinary.api.menu;

import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.inventory.StockRequestRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Daily plan item lifecycle: PLANNED -> INGREDIENTS_REVIEWED (confirm-ingredient-requirements)
 * -> READY (actual recorded); remaining counts down from the actual; the POS menu and order
 * creation only accept READY items; the admin planning view shows every item. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class DailyPlanStatusIntegrationTests {
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
    private String cashierToken;
    private long lunchId;
    private String today;
    private final List<Long> stockRequestIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window (12:00-14:30)
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        lunchId = periodId("lunch");
        today = LocalDate.now(clock).toString();
    }

    @AfterEach
    void tearDown() {
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        stockRequestIds.forEach(stockRequestRepository::deleteById);
        stockRequestIds.clear();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        shiftRepository.deleteAll();
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

    private long createComponent(String name) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"extraPrice\":15.00}";
        var response = mockMvc.perform(post("/admin/component-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long createOption(String name, int plannedPortions) throws Exception {
        var componentId = createComponent(name + " Base");
        var catalogBody = "{\"name\":\"" + name + "\",\"description\":\"desc\",\"price\":65.00,\"componentIds\":[" + componentId + "]}";
        var catalogResponse = mockMvc.perform(post("/admin/meal-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(catalogBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var catalogId = MAPPER.readTree(catalogResponse).get("id").asLong();

        var body = "{\"mealPeriodId\":" + lunchId + ",\"optionDate\":\"" + today + "\",\"mealCatalogId\":" + catalogId
                + ",\"plannedPortions\":" + plannedPortions + "}";
        var response = mockMvc.perform(post("/admin/daily-options").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long createComponentStock(String name, int bufferQuantity) throws Exception {
        var componentId = createComponent(name);
        var body = "{\"componentCatalogId\":" + componentId + ",\"mealPeriodId\":" + lunchId
                + ",\"optionDate\":\"" + today + "\",\"bufferQuantity\":" + bufferQuantity + "}";
        var response = mockMvc.perform(post("/admin/daily-component-stock").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    // No recipes on these components, so the confirmation raises an empty Stock Request — enough
    // to exercise the status transition without Main Store stock.
    private void confirmIngredients() throws Exception {
        var response = mockMvc.perform(post("/admin/daily-planning/" + today + "/confirm-ingredient-requirements")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"period\":\"LUNCH\",\"adjustments\":[]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        stockRequestIds.add(MAPPER.readTree(response).get("stockRequestId").asLong());
    }

    private void recordOptionActual(long optionId, int actual) throws Exception {
        mockMvc.perform(put("/admin/daily-options/" + optionId + "/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualPortions\":" + actual + "}"))
                .andExpect(status().isOk());
    }

    private void recordStockActual(long stockId, int actual) throws Exception {
        mockMvc.perform(put("/admin/daily-component-stock/" + stockId + "/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualQuantity\":" + actual + "}"))
                .andExpect(status().isOk());
    }

    private void openShift() throws Exception {
        mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingFloat\":500.00}"))
                .andExpect(status().isCreated());
    }

    private String orderBody(long optionId, int quantity, Long stockId, String amount) {
        var extras = stockId == null ? "[]" : "[{\"dailyComponentStockId\":" + stockId + ",\"quantity\":1}]";
        return "{\"payments\":[{\"method\":\"CASH\",\"amount\":" + amount + ",\"amountTendered\":" + amount + "}],"
                + "\"lines\":[{\"dailyMealOptionId\":" + optionId + ",\"quantity\":" + quantity + ",\"extras\":" + extras + "}]}";
    }

    private void sell(long optionId, int quantity) throws Exception {
        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(optionId, quantity, null, String.valueOf(65 * quantity))))
                .andExpect(status().isCreated());
    }

    @Test
    void newItems_startPlanned_andAdminViewShowsThemButPosDoesNot() throws Exception {
        createOption("Status Curry", 50);
        createComponentStock("Status Rice", 20);

        mockMvc.perform(get("/admin/daily-planning/" + today).param("period", "LUNCH")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[?(@.name == 'Status Curry')].status").value("PLANNED"))
                .andExpect(jsonPath("$.options[?(@.name == 'Status Curry')].plannedPortions").value(50))
                .andExpect(jsonPath("$.options[?(@.name == 'Status Curry')].actualPortions").value((Object) null))
                .andExpect(jsonPath("$.options[?(@.name == 'Status Curry')].sold").value(0))
                .andExpect(jsonPath("$.options[?(@.name == 'Status Curry')].portionsRemaining").value(0))
                .andExpect(jsonPath("$.componentStock[?(@.componentName == 'Status Rice')].status").value("PLANNED"))
                .andExpect(jsonPath("$.componentStock[?(@.componentName == 'Status Rice')].bufferQuantity").value(20))
                .andExpect(jsonPath("$.componentStock[?(@.componentName == 'Status Rice')].bufferRemaining").value(0));

        mockMvc.perform(get("/menu/today").param("period", "LUNCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[?(@.name == 'Status Curry')]").isEmpty())
                .andExpect(jsonPath("$.availableExtras[?(@.componentName == 'Status Rice')]").isEmpty());
    }

    @Test
    void confirm_movesCoveredItemsToReviewed_andLaterItemsStayPlanned() throws Exception {
        var optionId = createOption("Status Curry", 50);
        var stockId = createComponentStock("Status Rice", 20);

        confirmIngredients();
        var lateOptionId = createOption("Status Stew", 30);

        assertThat(dailyMealOptionRepository.findById(optionId).orElseThrow().getStatus())
                .isEqualTo(DailyPlanItemStatus.INGREDIENTS_REVIEWED);
        assertThat(dailyComponentStockRepository.findById(stockId).orElseThrow().getStatus())
                .isEqualTo(DailyPlanItemStatus.INGREDIENTS_REVIEWED);
        assertThat(dailyMealOptionRepository.findById(lateOptionId).orElseThrow().getStatus())
                .isEqualTo(DailyPlanItemStatus.PLANNED);

        // Still not on sale — reviewed, but no actual yet.
        mockMvc.perform(get("/menu/today").param("period", "LUNCH"))
                .andExpect(jsonPath("$.options[?(@.name == 'Status Curry')]").isEmpty());
    }

    @Test
    void recordActual_whilePlanned_isBadRequest() throws Exception {
        var optionId = createOption("Status Curry", 50);
        var stockId = createComponentStock("Status Rice", 20);

        mockMvc.perform(put("/admin/daily-options/" + optionId + "/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualPortions\":45}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("reviewed")));
        mockMvc.perform(put("/admin/daily-component-stock/" + stockId + "/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualQuantity\":20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("reviewed")));

        assertThat(dailyMealOptionRepository.findById(optionId).orElseThrow().getStatus())
                .isEqualTo(DailyPlanItemStatus.PLANNED);
    }

    @Test
    void recordActual_afterReview_makesItemReady_andPosSellsFromTheActual() throws Exception {
        var optionId = createOption("Status Curry", 50);
        var stockId = createComponentStock("Status Rice", 20);
        confirmIngredients();

        mockMvc.perform(put("/admin/daily-options/" + optionId + "/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualPortions\":45}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(optionId))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.plannedPortions").value(50))
                .andExpect(jsonPath("$.actualPortions").value(45))
                .andExpect(jsonPath("$.sold").value(0))
                .andExpect(jsonPath("$.portionsRemaining").value(45));
        mockMvc.perform(put("/admin/daily-component-stock/" + stockId + "/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualQuantity\":18}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.actualQuantity").value(18))
                .andExpect(jsonPath("$.bufferRemaining").value(18));

        mockMvc.perform(get("/menu/today").param("period", "LUNCH"))
                .andExpect(jsonPath("$.options[?(@.name == 'Status Curry')].status").value("READY"))
                .andExpect(jsonPath("$.options[?(@.name == 'Status Curry')].portionsRemaining").value(45))
                .andExpect(jsonPath("$.availableExtras[?(@.componentName == 'Status Rice')].bufferRemaining").value(18));

        openShift();
        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(optionId, 2, stockId, "145.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/admin/daily-planning/" + today).param("period", "LUNCH")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.options[?(@.id == " + optionId + ")].sold").value(2))
                .andExpect(jsonPath("$.options[?(@.id == " + optionId + ")].portionsRemaining").value(43))
                .andExpect(jsonPath("$.componentStock[?(@.id == " + stockId + ")].sold").value(1))
                .andExpect(jsonPath("$.componentStock[?(@.id == " + stockId + ")].bufferRemaining").value(17));
    }

    @Test
    void correctingActual_keepsSold_allowsAbovePlanned_andRejectsBelowSold() throws Exception {
        var optionId = createOption("Status Curry", 50);
        confirmIngredients();
        recordOptionActual(optionId, 45);
        openShift();
        sell(optionId, 3);

        // Higher than planned is allowed; remaining = actual - sold.
        mockMvc.perform(put("/admin/daily-options/" + optionId + "/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualPortions\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualPortions").value(60))
                .andExpect(jsonPath("$.sold").value(3))
                .andExpect(jsonPath("$.portionsRemaining").value(57));

        // Exactly the sold count is allowed — sells out.
        mockMvc.perform(put("/admin/daily-options/" + optionId + "/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualPortions\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portionsRemaining").value(0));

        mockMvc.perform(put("/admin/daily-options/" + optionId + "/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualPortions\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("actualPortions (2) cannot be lower than the 3 already sold."));

        var option = dailyMealOptionRepository.findById(optionId).orElseThrow();
        assertThat(option.getActualPortions()).isEqualTo(3);
        assertThat(option.getPortionsRemaining()).isZero();
    }

    @Test
    void componentStockActual_belowSold_isBadRequest() throws Exception {
        var optionId = createOption("Status Curry", 50);
        var stockId = createComponentStock("Status Rice", 20);
        confirmIngredients();
        recordOptionActual(optionId, 50);
        recordStockActual(stockId, 20);
        openShift();
        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(optionId, 1, stockId, "80.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/admin/daily-component-stock/" + stockId + "/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualQuantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("actualQuantity (0) cannot be lower than the 1 already sold."));
    }

    @Test
    void order_forItemNotReady_isRejected_evenWhenNamedDirectly() throws Exception {
        var readyOptionId = createOption("Status Pie", 10);
        var reviewedOptionId = createOption("Status Stew", 30);
        var stockId = createComponentStock("Status Rice", 20);
        confirmIngredients();
        recordOptionActual(readyOptionId, 10);
        var plannedOptionId = createOption("Status Bowl", 10); // after the confirmation
        openShift();

        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(plannedOptionId, 1, null, "65.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Meal option 'Status Bowl' is not ready for sale yet."));
        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(reviewedOptionId, 1, null, "65.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Meal option 'Status Stew' is not ready for sale yet."));
        // A READY option with a reviewed-but-not-READY extra is rejected as a whole.
        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(readyOptionId, 1, stockId, "80.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Extra component is not ready for sale yet."));

        assertThat(orderRepository.count()).isZero();
        assertThat(dailyMealOptionRepository.findById(readyOptionId).orElseThrow().getPortionsRemaining()).isEqualTo(10);
    }

    @Test
    void recordActual_validationAndUnknownId() throws Exception {
        mockMvc.perform(put("/admin/daily-options/999999999/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualPortions\":5}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/admin/daily-component-stock/999999999/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualQuantity\":5}"))
                .andExpect(status().isNotFound());

        var optionId = createOption("Status Curry", 50);
        mockMvc.perform(put("/admin/daily-options/" + optionId + "/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.actualPortions").value("actualPortions is required"));
        mockMvc.perform(put("/admin/daily-options/" + optionId + "/actual").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualPortions\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.actualPortions").value("actualPortions cannot be negative"));
    }

    @Test
    void adminEndpoints_areAdminOnly() throws Exception {
        var optionId = createOption("Status Curry", 50);

        mockMvc.perform(put("/admin/daily-options/" + optionId + "/actual").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actualPortions\":5}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/daily-planning/" + today).param("period", "LUNCH")
                        .header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminView_unknownPeriod_isBadRequest() throws Exception {
        mockMvc.perform(get("/admin/daily-planning/" + today).param("period", "SUPPER")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown meal period: SUPPER"));
    }
}
