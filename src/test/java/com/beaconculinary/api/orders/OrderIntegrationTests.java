package com.beaconculinary.api.orders;

import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the Stage 1.3 POST /orders endpoint (Stage 4's payments[] contract) and Stage 1.4
 * order-visibility endpoints against a real SQL Server database. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class OrderIntegrationTests {
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
    private long lunchId;
    private long breakfastId;
    private long allDayId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window (12:00-14:30)
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        lunchId = periodId("lunch");
        breakfastId = periodId("breakfast");
        allDayId = periodId("all day");
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

    private long createDailyComponentStock(long periodId, long componentCatalogId, int bufferQuantity) throws Exception {
        var today = LocalDate.now(clock).toString();
        var body = "{\"componentCatalogId\":" + componentCatalogId + ",\"mealPeriodId\":" + periodId
                + ",\"optionDate\":\"" + today + "\",\"bufferQuantity\":" + bufferQuantity + "}";
        var response = mockMvc.perform(post("/admin/daily-component-stock").header("Authorization", "Bearer " + adminToken)
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

    private record ExtraReq(long dailyComponentStockId, int quantity) {
    }

    private record LineReq(long dailyMealOptionId, int quantity, List<ExtraReq> extras) {
    }

    private record PaymentReq(String method, BigDecimal amount, BigDecimal amountTendered) {
    }

    private record OrderReq(List<PaymentReq> payments, List<LineReq> lines) {
    }

    // amount must equal the order's total exactly (Stage 4 Part A) — amountTendered defaults to
    // the same value (no change) unless a test needs to exercise change-due specifically.
    private String orderJson(BigDecimal amount, LineReq... lines) throws Exception {
        return orderJson(amount, amount, lines);
    }

    private String orderJson(BigDecimal amount, BigDecimal amountTendered, LineReq... lines) throws Exception {
        return MAPPER.writeValueAsString(new OrderReq(List.of(new PaymentReq("CASH", amount, amountTendered)), List.of(lines)));
    }

    private long placeOrder(String token, BigDecimal amount, LineReq... lines) throws Exception {
        var body = orderJson(amount, lines);
        var response = mockMvc.perform(post("/orders").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    @Test
    void singleLineNoExtras_happyPath() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        var body = orderJson(new BigDecimal("50.00"), new BigDecimal("100.00"), new LineReq(optionId, 1, List.of()));

        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(50.00))
                .andExpect(jsonPath("$.total").value(50.00))
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].method").value("CASH"))
                .andExpect(jsonPath("$.payments[0].amount").value(50.00))
                .andExpect(jsonPath("$.payments[0].amountTendered").value(100.00))
                .andExpect(jsonPath("$.payments[0].changeDue").value(50.00))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(50.00))
                .andExpect(jsonPath("$.orderNumber").exists());

        assertThat(dailyMealOptionRepository.findById(optionId).orElseThrow().getPortionsRemaining()).isEqualTo(9);
    }

    @Test
    void lineWithCrossDishExtra_sameMealPeriod_isAccepted() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var chickenId = createComponent("Chicken", "12.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        var chickenStockId = createDailyComponentStock(lunchId, chickenId, 5);
        openShift(cashierToken);

        var body = orderJson(new BigDecimal("62.00"),
                new LineReq(optionId, 1, List.of(new ExtraReq(chickenStockId, 1))));

        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(50.00))
                .andExpect(jsonPath("$.total").value(62.00))
                .andExpect(jsonPath("$.lines[0].extras[0].componentName").value("Chicken"))
                .andExpect(jsonPath("$.lines[0].extras[0].lineTotal").value(12.00));

        assertThat(dailyComponentStockRepository.findById(chickenStockId).orElseThrow().getBufferRemaining()).isEqualTo(4);
    }

    @Test
    void multiLineOrderAcrossTwoOptions_pricesBothCorrectly() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var riceId = createComponent("Rice", "8.00");
        var mealAId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var mealBId = createMealCatalog("Rice & Chicken", "45.00", riceId);
        var optionAId = createDailyOption(lunchId, mealAId, 10);
        var optionBId = createDailyOption(lunchId, mealBId, 10);
        openShift(cashierToken);

        var body = orderJson(new BigDecimal("145.00"),
                new LineReq(optionAId, 2, List.of()),
                new LineReq(optionBId, 1, List.of()));

        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(145.00))
                .andExpect(jsonPath("$.total").value(145.00))
                .andExpect(jsonPath("$.lines.length()").value(2));
    }

    @Test
    void insufficientPortions_returns409AndCreatesNoOrder() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 1);
        openShift(cashierToken);

        var firstBody = orderJson(new BigDecimal("50.00"), new LineReq(optionId, 1, List.of()));
        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isCreated());

        var secondBody = orderJson(new BigDecimal("50.00"), new LineReq(optionId, 1, List.of()));
        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isConflict());

        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentOrdersForLastPortion_exactlyOneSucceeds() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 1);
        // Stage 2.5's global single-open-shift constraint means only one cashier can have a
        // shift open system-wide, so this race is now exercised as two concurrent requests
        // from the one open shift rather than two different cashiers' shifts.
        openShift(cashierToken);

        var body = orderJson(new BigDecimal("50.00"), new LineReq(optionId, 1, List.of()));

        var results = runConcurrently(
                () -> mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus(),
                () -> mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus()
        );

        assertThat(results).containsExactlyInAnyOrder(201, 409);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(dailyMealOptionRepository.findById(optionId).orElseThrow().getPortionsRemaining()).isZero();
    }

    @Test
    void concurrentOrdersForLastSharedExtraUnit_acrossDifferentLines_exactlyOneSucceeds() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var chickenId = createComponent("Chicken", "12.00");
        var riceId = createComponent("Rice", "8.00");
        var mealAId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var mealBId = createMealCatalog("Rice & Chicken", "45.00", riceId);
        var optionAId = createDailyOption(lunchId, mealAId, 10);
        var optionBId = createDailyOption(lunchId, mealBId, 10);
        var chickenStockId = createDailyComponentStock(lunchId, chickenId, 1);
        // Stage 2.5's global single-open-shift constraint means only one cashier can have a
        // shift open system-wide, so this race is now exercised as two concurrent requests
        // from the one open shift rather than two different cashiers' shifts.
        openShift(cashierToken);

        var bodyA = orderJson(new BigDecimal("62.00"),
                new LineReq(optionAId, 1, List.of(new ExtraReq(chickenStockId, 1))));
        var bodyB = orderJson(new BigDecimal("57.00"),
                new LineReq(optionBId, 1, List.of(new ExtraReq(chickenStockId, 1))));

        var results = runConcurrently(
                () -> mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                                .contentType(MediaType.APPLICATION_JSON).content(bodyA))
                        .andReturn().getResponse().getStatus(),
                () -> mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                                .contentType(MediaType.APPLICATION_JSON).content(bodyB))
                        .andReturn().getResponse().getStatus()
        );

        assertThat(results).containsExactlyInAnyOrder(201, 409);
        assertThat(dailyComponentStockRepository.findById(chickenStockId).orElseThrow().getBufferRemaining()).isZero();
    }

    private List<Integer> runConcurrently(Callable<Integer> a, Callable<Integer> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = pool.invokeAll(List.of(a, b));
            return List.of(futures.get(0).get(), futures.get(1).get());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void underpayment_returns400AndCreatesNoOrder() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        // amount matches the order total (50.00) so the sum check passes; amountTendered
        // (10.00) is what's under test here — less than the CASH payment's own amount.
        var body = orderJson(new BigDecimal("50.00"), new BigDecimal("10.00"), new LineReq(optionId, 1, List.of()));

        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertThat(orderRepository.count()).isZero();
        assertThat(dailyMealOptionRepository.findById(optionId).orElseThrow().getPortionsRemaining()).isEqualTo(10);
    }

    @Test
    void noOpenShift_returns409() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);

        var body = orderJson(new BigDecimal("50.00"), new LineReq(optionId, 1, List.of()));

        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void optionOutsideMealPeriodWindow_returns400() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        clock.setTime(LocalTime.of(6, 0)); // before Lunch opens

        var body = orderJson(new BigDecimal("50.00"), new LineReq(optionId, 1, List.of()));

        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void allDayOption_isOrderableRegardlessOfWallClockTime() throws Exception {
        var mealId = createMealCatalog("Cool Drink", "15.00");
        var optionId = createDailyOption(allDayId, mealId, 10);
        openShift(cashierToken);

        clock.setTime(LocalTime.of(3, 0)); // well outside both Breakfast and Lunch windows

        var body = orderJson(new BigDecimal("15.00"), new LineReq(optionId, 1, List.of()));

        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(15.00));
    }

    @Test
    void extraStockedOnlyForDifferentMealPeriod_returns400() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var toastId = createComponent("Toast", "6.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        var toastStockId = createDailyComponentStock(breakfastId, toastId, 5); // breakfast-only stock
        openShift(cashierToken);

        var body = orderJson(new BigDecimal("56.00"),
                new LineReq(optionId, 1, List.of(new ExtraReq(toastStockId, 1))));

        mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ordersToday_returnsBothOrders_newestFirst() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        var firstId = placeOrder(cashierToken, new BigDecimal("50.00"), new LineReq(optionId, 1, List.of()));
        var secondId = placeOrder(cashierToken, new BigDecimal("50.00"), new LineReq(optionId, 1, List.of()));

        mockMvc.perform(get("/orders/today").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(secondId))
                .andExpect(jsonPath("$[1].id").value(firstId));
    }

    @Test
    void getOrderById_returnsFullLineAndExtraDetail() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var chickenId = createComponent("Chicken", "12.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        var chickenStockId = createDailyComponentStock(lunchId, chickenId, 5);
        openShift(cashierToken);

        var orderId = placeOrder(cashierToken, new BigDecimal("62.00"),
                new LineReq(optionId, 1, List.of(new ExtraReq(chickenStockId, 1))));

        mockMvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].extras.length()").value(1))
                .andExpect(jsonPath("$.lines[0].extras[0].componentName").value("Chicken"))
                .andExpect(jsonPath("$.printFailed").value(false));
    }

    @Test
    void getOrderById_nonexistent_returns404() throws Exception {
        mockMvc.perform(get("/orders/999999999").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void markPrintFailed_setsFlagWithoutChangingStatusOrTotals() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        var orderId = placeOrder(cashierToken, new BigDecimal("50.00"), new LineReq(optionId, 1, List.of()));

        mockMvc.perform(post("/orders/" + orderId + "/mark-print-failed").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.printFailed").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.total").value(50.00));
    }

    @Test
    void markPrintFailed_asAdmin_returns403() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        var orderId = placeOrder(cashierToken, new BigDecimal("50.00"), new LineReq(optionId, 1, List.of()));

        mockMvc.perform(post("/orders/" + orderId + "/mark-print-failed").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }
}
