package com.beaconculinary.api.shifts;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 2.5's expected-cash attribution formula needs real orders and adjustments to exercise
 * (unlike the rest of {@link ShiftIntegrationTests}), since it sums orders.original_total and
 * order_adjustments.amount, each scoped to a specific shift_id — so this class carries its own
 * meal-catalog/order-creation setup, mirroring OrderAdjustmentIntegrationTests (Stage 2.6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class ShiftCloseCashAttributionIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ShiftRepository shiftRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired
    private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired
    private AuthorizationTokenRepository authorizationTokenRepository;
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
        orderAdjustmentRepository.deleteAll();
        authorizationTokenRepository.deleteAll();
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

    private record ExtraReq(long dailyComponentStockId, int quantity) {
    }

    private record LineReq(long dailyMealOptionId, int quantity, List<ExtraReq> extras) {
    }

    private record PaymentReq(String method, BigDecimal amount, BigDecimal amountTendered) {
    }

    private record OrderReq(List<PaymentReq> payments, List<LineReq> lines) {
    }

    // amount must equal the order's total exactly (Stage 4 Part A) — amountTendered is fixed
    // comfortably larger, since no test here asserts change-due.
    private long placeOrder(BigDecimal amount, LineReq... lines) throws Exception {
        var body = MAPPER.writeValueAsString(new OrderReq(
                List.of(new PaymentReq("CASH", amount, amount.add(new BigDecimal("500.00")))), List.of(lines)));
        var response = mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private void patchStatus(long orderId, String status) throws Exception {
        mockMvc.perform(patch("/kitchen/orders/" + orderId + "/status").header("Authorization", "Bearer " + kitchenToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private record OpenShiftReq(BigDecimal openingFloat) {
    }

    private long openShift(String token, String openingFloat) throws Exception {
        var body = MAPPER.writeValueAsString(new OpenShiftReq(new BigDecimal(openingFloat)));
        var response = mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private record VarianceAuthReq(String reasonCode, String note, String authorizationToken) {
    }

    private record CloseShiftReq(BigDecimal countedCash, VarianceAuthReq varianceAuthorization) {
    }

    private void closeShift(long shiftId, String countedCash) throws Exception {
        var body = MAPPER.writeValueAsString(new CloseShiftReq(new BigDecimal(countedCash), null));
        mockMvc.perform(post("/shifts/" + shiftId + "/close").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private record AuthorizeReq(String pin) {
    }

    private String authorizePin() throws Exception {
        var body = MAPPER.writeValueAsString(new AuthorizeReq("654321"));
        var response = mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("authorizationToken").asText();
    }

    private record AdjustmentReq(String scope, String reasonCode, String note, String authorizationToken) {
    }

    private void adjust(long orderId, String scope, String reasonCode) throws Exception {
        var body = MAPPER.writeValueAsString(new AdjustmentReq(scope, reasonCode, null, authorizePin()));
        mockMvc.perform(post("/orders/" + orderId + "/adjustments").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void expectedCash_reflectsCashSalesPlusSameShiftWholeOrderVoidAndExtrasOnlyRefund() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var chickenId = createComponent("Chicken", "10.00");
        var mealId = createMealCatalog("Meal", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        var chickenStockId = createDailyComponentStock(lunchId, chickenId, 5);

        openShift(cashierToken, "500.00");

        // Cash sale #1: 50.00, left PENDING -> a later WHOLE_ORDER adjustment reads as VOID.
        var order1 = placeOrder(new BigDecimal("50.00"), new LineReq(optionId, 1, List.of()));

        // Cash sale #2: 50.00 meal + 10.00 extra = 60.00, driven to DONE -> a later EXTRAS_ONLY
        // adjustment reads as REFUND.
        var order2 = placeOrder(new BigDecimal("60.00"),
                new LineReq(optionId, 1, List.of(new ExtraReq(chickenStockId, 1))));
        patchStatus(order2, "IN_PROGRESS");
        patchStatus(order2, "DONE");

        adjust(order1, "WHOLE_ORDER", "CUSTOMER_COMPLAINT"); // VOID, amount 50.00
        adjust(order2, "EXTRAS_ONLY", "OUT_OF_STOCK_ERROR"); // REFUND, amount 10.00

        var shiftId = shiftRepository.findFirstByStatus(ShiftStatus.OPEN).orElseThrow().getId();

        // cashSalesTotal = 50.00 + 60.00 = 110.00 (original_total, unreduced by the adjustments)
        // adjustmentsTotal = 50.00 + 10.00 = 60.00
        // expectedCash = 500.00 + 110.00 - 60.00 = 550.00
        mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingFloat").value(500.00))
                .andExpect(jsonPath("$.cashSalesTotal").value(110.00))
                .andExpect(jsonPath("$.adjustmentsTotal").value(60.00))
                .andExpect(jsonPath("$.expectedCash").value(550.00))
                .andExpect(jsonPath("$.orderCount").value(2));
    }

    @Test
    void crossShiftAttribution_refundProcessedInLaterShift_reducesLaterShiftNotOriginal() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Meal", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);

        var shiftAId = openShift(cashierToken, "500.00");
        var orderId = placeOrder(new BigDecimal("50.00"), new LineReq(optionId, 1, List.of()));
        patchStatus(orderId, "IN_PROGRESS");
        patchStatus(orderId, "DONE");

        // Shift A: expectedCash = 500.00 + 50.00 - 0 = 550.00 — close with zero variance.
        closeShift(shiftAId, "550.00");

        var shiftBId = openShift(cashierToken, "200.00");
        // Refund the shift-A order while shift B is the one currently open — Stage 2.5 sets
        // the adjustment's shift_id to shift B, not order.shift (still shift A).
        adjust(orderId, "WHOLE_ORDER", "KITCHEN_ERROR"); // order is DONE -> REFUND, amount 50.00

        // Shift A's math is unaffected: adjustmentsTotal for shift A is still 0 because the
        // refund's shift_id is B, not A.
        mockMvc.perform(get("/shifts/" + shiftAId + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashSalesTotal").value(50.00))
                .andExpect(jsonPath("$.adjustmentsTotal").value(0.00))
                .andExpect(jsonPath("$.expectedCash").value(550.00));

        // Shift B absorbs the refund: no cash sales of its own, adjustmentsTotal 50.00.
        mockMvc.perform(get("/shifts/" + shiftBId + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashSalesTotal").value(0.00))
                .andExpect(jsonPath("$.adjustmentsTotal").value(50.00))
                .andExpect(jsonPath("$.expectedCash").value(150.00));
    }
}
