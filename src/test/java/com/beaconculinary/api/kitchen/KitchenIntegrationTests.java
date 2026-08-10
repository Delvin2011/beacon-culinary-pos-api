package com.beaconculinary.api.kitchen;

import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the Stage 2.1 kitchen status-screen endpoints against a real SQL Server database. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class KitchenIntegrationTests {
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
    private ComponentCatalogRepository componentCatalogRepository;
    @Autowired
    private RecipeRepository recipeRepository;
    @Autowired
    private MutableClock clock;

    private String adminToken;
    private String cashierToken;
    private String kitchenToken;
    private long lunchId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window (12:00-14:30)
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        kitchenToken = AuthTestHelper.loginAsKitchen(mockMvc);
        lunchId = periodId("lunch");
    }

    @AfterEach
    void tearDown() {
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
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

    private long createComponent(String name, String extraPrice) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"extraPrice\":" + extraPrice + "}";
        var response = mockMvc.perform(post("/admin/component-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long createMealCatalog(String name, String price, long componentId) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"description\":\"desc\",\"price\":" + price
                + ",\"componentIds\":[" + componentId + "]}";
        var response = mockMvc.perform(post("/admin/meal-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long createDailyOption(long periodId, long mealCatalogId, int plannedPortions) throws Exception {
        var today = LocalDate.now(clock).toString();
        var body = "{\"mealPeriodId\":" + periodId + ",\"optionDate\":\"" + today + "\",\"mealCatalogId\":" + mealCatalogId
                + ",\"plannedPortions\":" + plannedPortions + "}";
        var response = mockMvc.perform(post("/admin/daily-options").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private void openShift(String token) throws Exception {
        mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingFloat\":500.00}"))
                .andExpect(status().isCreated());
    }

    // The one meal catalog entry used in this file is always 50.00 x1, so the payment amount
    // (which must equal the order total exactly) is always 50.00.
    private long placeOrder(long optionId) throws Exception {
        var body = "{\"payments\":[{\"method\":\"CASH\",\"amount\":50.00,\"amountTendered\":100.00}],"
                + "\"lines\":[{\"dailyMealOptionId\":" + optionId + ",\"quantity\":1,\"extras\":[]}]}";
        var response = mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long setUpOrder() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);
        return placeOrder(optionId);
    }

    private void patchStatus(String token, long orderId, String status) throws Exception {
        mockMvc.perform(patch("/kitchen/orders/" + orderId + "/status").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void newOrder_appearsInActiveQueue_asPending_withAuditEvent() throws Exception {
        var orderId = setUpOrder();

        mockMvc.perform(get("/kitchen/orders").header("Authorization", "Bearer " + kitchenToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(orderId))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].lines[0].optionName").value("Potatoes & Beef"));

        var events = orderStatusEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFromStatus()).isNull();
        assertThat(events.get(0).getToStatus().name()).isEqualTo("PENDING");
    }

    @Test
    void pendingToInProgress_succeeds_andLogsEvent() throws Exception {
        var orderId = setUpOrder();

        mockMvc.perform(patch("/kitchen/orders/" + orderId + "/status").header("Authorization", "Bearer " + kitchenToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus().name()).isEqualTo("IN_PROGRESS");
        assertThat(orderStatusEventRepository.findAll()).hasSize(2); // creation + this transition
    }

    @Test
    void inProgressToDone_succeeds_andLeavesActiveQueue() throws Exception {
        var orderId = setUpOrder();
        patchStatus(kitchenToken, orderId, "IN_PROGRESS");
        patchStatus(kitchenToken, orderId, "DONE");

        mockMvc.perform(get("/kitchen/orders").header("Authorization", "Bearer " + kitchenToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        assertThat(orderStatusEventRepository.findAll()).hasSize(3); // creation + 2 transitions
    }

    @Test
    void skippingInProgress_pendingToDone_returns400() throws Exception {
        var orderId = setUpOrder();

        mockMvc.perform(patch("/kitchen/orders/" + orderId + "/status").header("Authorization", "Bearer " + kitchenToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void targetingCollected_returns400_regardlessOfCurrentStatus() throws Exception {
        var orderId = setUpOrder();

        mockMvc.perform(patch("/kitchen/orders/" + orderId + "/status").header("Authorization", "Bearer " + kitchenToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COLLECTED\"}"))
                .andExpect(status().isBadRequest());

        patchStatus(kitchenToken, orderId, "IN_PROGRESS");
        patchStatus(kitchenToken, orderId, "DONE");

        mockMvc.perform(patch("/kitchen/orders/" + orderId + "/status").header("Authorization", "Bearer " + kitchenToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COLLECTED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void repeatingTransitionOnDoneOrder_returns400() throws Exception {
        var orderId = setUpOrder();
        patchStatus(kitchenToken, orderId, "IN_PROGRESS");
        patchStatus(kitchenToken, orderId, "DONE");

        mockMvc.perform(patch("/kitchen/orders/" + orderId + "/status").header("Authorization", "Bearer " + kitchenToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cashierRole_isForbiddenOnAllKitchenEndpoints() throws Exception {
        var orderId = setUpOrder();

        mockMvc.perform(get("/kitchen/orders").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/kitchen/orders/" + orderId + "/status").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/kitchen/orders/stream").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void kitchenAndAdminRoles_canAccessKitchenEndpoints() throws Exception {
        var orderId = setUpOrder();

        mockMvc.perform(get("/kitchen/orders").header("Authorization", "Bearer " + kitchenToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/kitchen/orders").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/kitchen/orders/" + orderId + "/status").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void previousDayOrder_isExcludedFromActiveQueue() throws Exception {
        var orderId = setUpOrder();
        var order = orderRepository.findById(orderId).orElseThrow();
        order.setOrderDate(LocalDate.now(clock).minusDays(1));
        orderRepository.save(order);

        mockMvc.perform(get("/kitchen/orders").header("Authorization", "Bearer " + kitchenToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void sseStream_deliversStatusChangedEvent_onPatch() throws Exception {
        var orderId = setUpOrder();

        var streamResult = mockMvc.perform(get("/kitchen/orders/stream").header("Authorization", "Bearer " + kitchenToken))
                .andExpect(request().asyncStarted())
                .andReturn();

        patchStatus(kitchenToken, orderId, "IN_PROGRESS");

        var streamed = streamResult.getResponse().getContentAsString();
        assertThat(streamed).contains("STATUS_CHANGED");
        assertThat(streamed).contains("\"status\":\"IN_PROGRESS\"");
        assertThat(streamed).contains("\"orderId\":" + orderId);
    }
}
