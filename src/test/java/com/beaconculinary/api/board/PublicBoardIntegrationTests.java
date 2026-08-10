package com.beaconculinary.api.board;

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

/** Exercises the Stage 2.2 public display board endpoints against a real SQL Server database. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class PublicBoardIntegrationTests {
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

    private void patchStatus(long orderId, String status) throws Exception {
        mockMvc.perform(patch("/kitchen/orders/" + orderId + "/status").header("Authorization", "Bearer " + kitchenToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void today_succeedsWithNoAuthorizationHeader() throws Exception {
        setUpOrder();

        mockMvc.perform(get("/public/board/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1));
    }

    @Test
    void today_payloadIsMinimal_noLineItemsOrOrderIdOrCashierData() throws Exception {
        var orderId = setUpOrder();

        var response = mockMvc.perform(get("/public/board/today"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var node = MAPPER.readTree(response).get("orders").get(0);
        assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder("orderNumber", "status");
        assertThat(node.has("orderId")).isFalse();
        assertThat(node.has("id")).isFalse();
        assertThat(node.has("lines")).isFalse();
        assertThat(node.has("cashierId")).isFalse();
        assertThat(node.has("createdAt")).isFalse();
        // Sanity: the underlying order really does have an id distinct from what's exposed here.
        assertThat(orderId).isPositive();
    }

    @Test
    void statusChangeViaKitchenPatch_appearsOnPublicStream() throws Exception {
        var orderId = setUpOrder();

        var streamResult = mockMvc.perform(get("/public/board/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        patchStatus(orderId, "IN_PROGRESS");

        var streamed = streamResult.getResponse().getContentAsString();
        assertThat(streamed).contains("STATUS_CHANGED");
        assertThat(streamed).contains("\"status\":\"IN_PROGRESS\"");
        assertThat(streamed).doesNotContain("orderId");
        assertThat(streamed).doesNotContain("lines");
    }

    @Test
    void stream_succeedsWithNoAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/public/board/stream"))
                .andExpect(request().asyncStarted());
    }

    @Test
    void previousDayOrder_isExcludedFromTodayBoard() throws Exception {
        var orderId = setUpOrder();
        var order = orderRepository.findById(orderId).orElseThrow();
        order.setOrderDate(LocalDate.now(clock).minusDays(1));
        orderRepository.save(order);

        mockMvc.perform(get("/public/board/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(0));
    }

    @Test
    void twoSimultaneousSubscribers_bothReceiveTheSameEvent() throws Exception {
        var orderId = setUpOrder();

        var clientA = mockMvc.perform(get("/public/board/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        var clientB = mockMvc.perform(get("/public/board/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        patchStatus(orderId, "IN_PROGRESS");

        var streamedA = clientA.getResponse().getContentAsString();
        var streamedB = clientB.getResponse().getContentAsString();
        assertThat(streamedA).contains("STATUS_CHANGED").contains("\"status\":\"IN_PROGRESS\"");
        assertThat(streamedB).contains("STATUS_CHANGED").contains("\"status\":\"IN_PROGRESS\"");
    }

    @Test
    void doneOrder_remainsOnBoard_untilStage2_3Collects() throws Exception {
        var orderId = setUpOrder();
        patchStatus(orderId, "IN_PROGRESS");
        patchStatus(orderId, "DONE");

        mockMvc.perform(get("/public/board/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].status").value("DONE"));
    }
}
