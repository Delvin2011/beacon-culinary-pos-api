package com.beaconculinary.api.orders;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.shifts.ShiftStatus;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises Stage 3.2's DISCOUNT branch on POST /orders/{id}/adjustments, sharing the
 * order_adjustments table and admin-authorization flow with Stage 2.6's void/refund. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class OrderDiscountIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired
    private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired
    private AuthorizationTokenRepository authorizationTokenRepository;
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
    private boolean shiftOpened = false;

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
        if (shiftOpened) {
            return;
        }
        mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingFloat\":500.00}"))
                .andExpect(status().isCreated());
        shiftOpened = true;
    }

    private record LineReq(long dailyMealOptionId, int quantity, List<Object> extras) {
    }

    private record PaymentReq(String method, BigDecimal amount, BigDecimal amountTendered) {
    }

    private record OrderReq(List<PaymentReq> payments, List<LineReq> lines) {
    }

    // amount must equal the order's total exactly (Stage 4 Part A); amountTendered is fixed at
    // a comfortably larger value since no test here asserts change-due.
    private long placeOrder(BigDecimal amount, long optionId, int quantity) throws Exception {
        var body = MAPPER.writeValueAsString(new OrderReq(
                List.of(new PaymentReq("CASH", amount, amount.add(new BigDecimal("500.00")))),
                List.of(new LineReq(optionId, quantity, List.of()))));
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

    private record AuthorizeReq(String pin) {
    }

    private String authorize() throws Exception {
        var body = MAPPER.writeValueAsString(new AuthorizeReq("654321"));
        var response = mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("authorizationToken").asText();
    }

    private record DiscountReq(String requestedAction, String discountType, BigDecimal discountValue,
                                String reasonCode, String note, String authorizationToken) {
    }

    private ResultActions discount(long orderId, String discountType, BigDecimal discountValue, String token) throws Exception {
        var body = MAPPER.writeValueAsString(new DiscountReq("DISCOUNT", discountType, discountValue, "CUSTOMER_COMPLAINT", null, token));
        return mockMvc.perform(post("/orders/" + orderId + "/adjustments").header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private record CancelReq(String scope, String reasonCode, String note, String authorizationToken) {
    }

    private ResultActions cancel(long orderId, String scope, String token) throws Exception {
        var body = MAPPER.writeValueAsString(new CancelReq(scope, "CUSTOMER_COMPLAINT", null, token));
        return mockMvc.perform(post("/orders/" + orderId + "/adjustments").header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // Only ever called with quantity=1, so the order's total is exactly `price`.
    private long setUpOrder(String price, int quantity) throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Meal " + price + "-" + quantity, price, beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);
        return placeOrder(new BigDecimal(price), optionId, quantity);
    }

    @Test
    void percentageDiscount_computesAndDeductsCorrectAmount_statusUnchanged() throws Exception {
        var orderId = setUpOrder("50.00", 1);
        var token = authorize();

        discount(orderId, "PERCENTAGE", BigDecimal.TEN, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.total").value(45.00))
                .andExpect(jsonPath("$.adjustments[0].action").value("DISCOUNT"))
                .andExpect(jsonPath("$.adjustments[0].discountType").value("PERCENTAGE"))
                .andExpect(jsonPath("$.adjustments[0].amount").value(5.00));
    }

    @Test
    void fixedAmountDiscount_deductsExactAmount_statusUnchanged() throws Exception {
        var orderId = setUpOrder("50.00", 1);
        var token = authorize();

        discount(orderId, "FIXED_AMOUNT", new BigDecimal("15.00"), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.total").value(35.00))
                .andExpect(jsonPath("$.adjustments[0].action").value("DISCOUNT"))
                .andExpect(jsonPath("$.adjustments[0].discountType").value("FIXED_AMOUNT"))
                .andExpect(jsonPath("$.adjustments[0].amount").value(15.00));
    }

    @Test
    void discountExceedingTotal_returns400_orderUntouched() throws Exception {
        var orderId = setUpOrder("50.00", 1);
        var token = authorize();

        discount(orderId, "FIXED_AMOUNT", new BigDecimal("60.00"), token)
                .andExpect(status().isBadRequest());

        assertThat(orderRepository.findById(orderId).orElseThrow().getTotal()).isEqualByComparingTo("50.00");
    }

    @Test
    void percentageDiscount_outOfRange_returns400() throws Exception {
        var orderId = setUpOrder("50.00", 1);
        var token = authorize();

        discount(orderId, "PERCENTAGE", new BigDecimal("150"), token)
                .andExpect(status().isBadRequest());
    }

    @Test
    void fixedAmountDiscount_zeroOrNegative_returns400() throws Exception {
        var orderId = setUpOrder("50.00", 1);
        var token = authorize();

        discount(orderId, "FIXED_AMOUNT", BigDecimal.ZERO, token)
                .andExpect(status().isBadRequest());
    }

    @Test
    void twoStackedDiscounts_bothSucceed_totalReflectsBoth() throws Exception {
        var orderId = setUpOrder("100.00", 1);

        var firstToken = authorize();
        discount(orderId, "FIXED_AMOUNT", new BigDecimal("30.00"), firstToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(70.00));

        var secondToken = authorize();
        discount(orderId, "PERCENTAGE", new BigDecimal("50"), secondToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(35.00))
                .andExpect(jsonPath("$.adjustments.length()").value(2));
    }

    @Test
    void discountFollowedByWholeOrderRefund_refundAmountEqualsPostDiscountTotal() throws Exception {
        var orderId = setUpOrder("50.00", 1);
        patchStatus(orderId, "IN_PROGRESS");
        patchStatus(orderId, "DONE");

        var discountToken = authorize();
        discount(orderId, "FIXED_AMOUNT", new BigDecimal("20.00"), discountToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(30.00));

        var refundToken = authorize();
        cancel(orderId, "WHOLE_ORDER", refundToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.total").value(0.00))
                .andExpect(jsonPath("$.adjustments[1].action").value("REFUND"))
                .andExpect(jsonPath("$.adjustments[1].amount").value(30.00));
    }

    @Test
    void discountOnAlreadyVoidedOrder_isRejected() throws Exception {
        var orderId = setUpOrder("50.00", 1);
        var voidToken = authorize();
        cancel(orderId, "WHOLE_ORDER", voidToken).andExpect(status().isCreated());

        var discountToken = authorize();
        // Reuses Stage 2.6's shared terminal-order guard (order.status VOIDED/REFUNDED), which
        // reports as 409 CONFLICT — same status already used for a repeat void/refund attempt.
        discount(orderId, "FIXED_AMOUNT", new BigDecimal("5.00"), discountToken)
                .andExpect(status().isConflict());
    }

    @Test
    void shiftClose_discountReducesExpectedCash_withNoStage25CodeChanges() throws Exception {
        var orderId = setUpOrder("100.00", 1);
        var token = authorize();

        discount(orderId, "FIXED_AMOUNT", new BigDecimal("20.00"), token).andExpect(status().isCreated());

        var shiftId = shiftRepository.findFirstByStatus(ShiftStatus.OPEN).orElseThrow().getId();

        // cashSalesTotal = 100.00 (original_total, unreduced by the discount)
        // adjustmentsTotal = 20.00 (the discount's amount, summed the same as any other adjustment)
        // expectedCash = 500.00 + 100.00 - 20.00 = 580.00
        mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashSalesTotal").value(100.00))
                .andExpect(jsonPath("$.adjustmentsTotal").value(20.00))
                .andExpect(jsonPath("$.expectedCash").value(580.00));
    }
}
