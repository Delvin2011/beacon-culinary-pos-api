package com.beaconculinary.api.orders;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.inventory.RecipeRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the Stage 2.6 admin-gated void/refund endpoints against a real SQL Server database. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class OrderAdjustmentIntegrationTests {
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

    // amount must equal the order's total exactly (Stage 4 Part A) — amountTendered can be
    // larger, none of this file's assertions depend on the exact change-due figure.
    private long placeOrder(BigDecimal amount, LineReq... lines) throws Exception {
        var body = MAPPER.writeValueAsString(new OrderReq(List.of(new PaymentReq("CASH", amount, new BigDecimal("100.00"))), List.of(lines)));
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

    private record OrderSetup(long orderId, long optionId, long chickenStockId) {
    }

    private boolean shiftOpened = false;

    /** A PENDING order: one line (Potatoes & Beef, 50.00) plus one cross-dish extra (Chicken, 12.00) — total 62.00.
     * Reuses a single open shift across calls within the same test — the cashier can only have one open at a time. */
    private OrderSetup setUpOrderWithExtra() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var chickenId = createComponent("Chicken", "12.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        var chickenStockId = createDailyComponentStock(lunchId, chickenId, 5);
        if (!shiftOpened) {
            openShift(cashierToken);
            shiftOpened = true;
        }
        var orderId = placeOrder(new BigDecimal("62.00"),
                new LineReq(optionId, 1, List.of(new ExtraReq(chickenStockId, 1))));
        return new OrderSetup(orderId, optionId, chickenStockId);
    }

    private record AuthorizeReq(String pin) {
    }

    private String authorize(String pin) throws Exception {
        var body = MAPPER.writeValueAsString(new AuthorizeReq(pin));
        var response = mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("authorizationToken").asText();
    }

    private record AdjustmentReq(String scope, String reasonCode, String note, String authorizationToken) {
    }

    private ResultActions adjust(long orderId, String scope, String reasonCode, String note, String token) throws Exception {
        var body = MAPPER.writeValueAsString(new AdjustmentReq(scope, reasonCode, note, token));
        return mockMvc.perform(post("/orders/" + orderId + "/adjustments").header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void authorize_withValidAdminPin_returnsToken() throws Exception {
        mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(new AuthorizeReq("654321"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationToken").exists())
                .andExpect(jsonPath("$.adminId").value(AuthTestHelper.ADMIN_ID))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void authorize_withInvalidPin_returns401() throws Exception {
        mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(new AuthorizeReq("000000"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wholeOrderVoid_onPendingOrder_restoresStockAndTerminatesOrder() throws Exception {
        var setup = setUpOrderWithExtra();
        var token = authorize("654321");

        adjust(setup.orderId(), "WHOLE_ORDER", "CUSTOMER_COMPLAINT", null, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.total").value(0.00))
                .andExpect(jsonPath("$.originalTotal").value(62.00))
                .andExpect(jsonPath("$.adjustments.length()").value(1))
                .andExpect(jsonPath("$.adjustments[0].action").value("VOID"))
                .andExpect(jsonPath("$.adjustments[0].amount").value(62.00));

        assertThat(dailyMealOptionRepository.findById(setup.optionId()).orElseThrow().getPortionsRemaining()).isEqualTo(10);
        assertThat(dailyComponentStockRepository.findById(setup.chickenStockId()).orElseThrow().getBufferRemaining()).isEqualTo(5);

        mockMvc.perform(get("/kitchen/orders").header("Authorization", "Bearer " + kitchenToken))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/public/board/today"))
                .andExpect(jsonPath("$.orders.length()").value(0));
    }

    @Test
    void wholeOrderRefund_onDoneOrder_doesNotRestoreStock() throws Exception {
        var setup = setUpOrderWithExtra();
        patchStatus(setup.orderId(), "IN_PROGRESS");
        patchStatus(setup.orderId(), "DONE");
        var token = authorize("654321");

        adjust(setup.orderId(), "WHOLE_ORDER", "KITCHEN_ERROR", null, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.total").value(0.00))
                .andExpect(jsonPath("$.adjustments[0].action").value("REFUND"))
                .andExpect(jsonPath("$.adjustments[0].amount").value(62.00));

        assertThat(dailyMealOptionRepository.findById(setup.optionId()).orElseThrow().getPortionsRemaining()).isEqualTo(9);
        assertThat(dailyComponentStockRepository.findById(setup.chickenStockId()).orElseThrow().getBufferRemaining()).isEqualTo(4);
    }

    @Test
    void extrasOnlyVoid_onPendingOrder_restoresOnlyExtraStock_andFiresKitchenEvent() throws Exception {
        var setup = setUpOrderWithExtra();
        var token = authorize("654321");

        var streamResult = mockMvc.perform(get("/kitchen/orders/stream").header("Authorization", "Bearer " + kitchenToken))
                .andExpect(request().asyncStarted())
                .andReturn();

        adjust(setup.orderId(), "EXTRAS_ONLY", "WRONG_ORDER", null, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.total").value(50.00))
                .andExpect(jsonPath("$.adjustments[0].action").value("VOID"))
                .andExpect(jsonPath("$.adjustments[0].amount").value(12.00));

        assertThat(dailyMealOptionRepository.findById(setup.optionId()).orElseThrow().getPortionsRemaining()).isEqualTo(9);
        assertThat(dailyComponentStockRepository.findById(setup.chickenStockId()).orElseThrow().getBufferRemaining()).isEqualTo(5);

        var streamed = streamResult.getResponse().getContentAsString();
        assertThat(streamed).contains("ORDER_UPDATED");

        mockMvc.perform(get("/kitchen/orders").header("Authorization", "Bearer " + kitchenToken))
                .andExpect(jsonPath("$[0].lines[0].extras.length()").value(0));
    }

    @Test
    void extrasOnlyRefund_onDoneOrder_reducesTotalWithoutStockOrKitchenEvent() throws Exception {
        var setup = setUpOrderWithExtra();
        patchStatus(setup.orderId(), "IN_PROGRESS");
        patchStatus(setup.orderId(), "DONE");
        var token = authorize("654321");

        var streamResult = mockMvc.perform(get("/kitchen/orders/stream").header("Authorization", "Bearer " + kitchenToken))
                .andExpect(request().asyncStarted())
                .andReturn();

        adjust(setup.orderId(), "EXTRAS_ONLY", "OUT_OF_STOCK_ERROR", null, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.total").value(50.00))
                .andExpect(jsonPath("$.adjustments[0].action").value("REFUND"));

        assertThat(dailyComponentStockRepository.findById(setup.chickenStockId()).orElseThrow().getBufferRemaining()).isEqualTo(4);

        var streamed = streamResult.getResponse().getContentAsString();
        assertThat(streamed).doesNotContain("ORDER_UPDATED");
    }

    @Test
    void secondExtrasOnlyAdjustment_returns409_andFirstAdjustmentEffectsUnchanged() throws Exception {
        var setup = setUpOrderWithExtra();
        var firstToken = authorize("654321");
        adjust(setup.orderId(), "EXTRAS_ONLY", "WRONG_ORDER", null, firstToken).andExpect(status().isCreated());

        var secondToken = authorize("654321");
        adjust(setup.orderId(), "EXTRAS_ONLY", "WRONG_ORDER", null, secondToken)
                .andExpect(status().isConflict());

        var order = orderRepository.findById(setup.orderId()).orElseThrow();
        assertThat(order.getTotal()).isEqualByComparingTo("50.00");
        assertThat(orderAdjustmentRepository.findAll()).hasSize(1);
    }

    @Test
    void adjustmentOnAlreadyVoidedOrder_returns409() throws Exception {
        var setup = setUpOrderWithExtra();
        var firstToken = authorize("654321");
        adjust(setup.orderId(), "WHOLE_ORDER", "WRONG_ORDER", null, firstToken).andExpect(status().isCreated());

        var secondToken = authorize("654321");
        adjust(setup.orderId(), "EXTRAS_ONLY", "WRONG_ORDER", null, secondToken)
                .andExpect(status().isConflict());
    }

    @Test
    void adjustmentOnPriorDayOrder_returns400() throws Exception {
        var setup = setUpOrderWithExtra();
        var order = orderRepository.findById(setup.orderId()).orElseThrow();
        order.setOrderDate(LocalDate.now(clock).minusDays(1));
        orderRepository.save(order);
        var token = authorize("654321");

        adjust(setup.orderId(), "WHOLE_ORDER", "WRONG_ORDER", null, token)
                .andExpect(status().isBadRequest());
    }

    @Test
    void otherReasonCode_withoutNote_returns400_withNote_succeeds() throws Exception {
        var setup = setUpOrderWithExtra();
        var tokenWithoutNote = authorize("654321");
        adjust(setup.orderId(), "WHOLE_ORDER", "OTHER", null, tokenWithoutNote)
                .andExpect(status().isBadRequest());

        var tokenWithNote = authorize("654321");
        adjust(setup.orderId(), "WHOLE_ORDER", "OTHER", "Manager override, see till log.", tokenWithNote)
                .andExpect(status().isCreated());
    }

    @Test
    void reusingAToken_isRejectedOnSecondAdjustmentCall() throws Exception {
        var setup = setUpOrderWithExtra();
        var token = authorize("654321");
        adjust(setup.orderId(), "EXTRAS_ONLY", "WRONG_ORDER", null, token).andExpect(status().isCreated());

        var another = setUpOrderWithExtra();
        adjust(another.orderId(), "EXTRAS_ONLY", "WRONG_ORDER", null, token)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredToken_isRejected_evenThoughUnused() throws Exception {
        var setup = setUpOrderWithExtra();
        var token = authorize("654321");
        clock.setTime(LocalTime.of(12, 31, 5)); // > 60s after the 12:30:00 authorize call

        adjust(setup.orderId(), "EXTRAS_ONLY", "WRONG_ORDER", null, token)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidRequestFailure_stillBurnsTheToken() throws Exception {
        var setup = setUpOrderWithExtra();
        var token = authorize("654321");
        // OTHER without a note fails request validation *after* the token is consumed.
        adjust(setup.orderId(), "WHOLE_ORDER", "OTHER", null, token).andExpect(status().isBadRequest());

        adjust(setup.orderId(), "WHOLE_ORDER", "WRONG_ORDER", null, token)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wholeOrderAfterExtrasOnly_amountReflectsOnlyRemainingTotal() throws Exception {
        var setup = setUpOrderWithExtra();
        var extrasToken = authorize("654321");
        adjust(setup.orderId(), "EXTRAS_ONLY", "WRONG_ORDER", null, extrasToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(50.00));

        var wholeOrderToken = authorize("654321");
        adjust(setup.orderId(), "WHOLE_ORDER", "CUSTOMER_COMPLAINT", null, wholeOrderToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.total").value(0.00))
                .andExpect(jsonPath("$.adjustments.length()").value(2))
                .andExpect(jsonPath("$.adjustments[1].amount").value(50.00));

        // Extra's stock was already restored by the EXTRAS_ONLY void — must not be restored twice.
        assertThat(dailyComponentStockRepository.findById(setup.chickenStockId()).orElseThrow().getBufferRemaining()).isEqualTo(5);
        assertThat(dailyMealOptionRepository.findById(setup.optionId()).orElseThrow().getPortionsRemaining()).isEqualTo(10);
    }

    @Test
    void nonAdminCannotBeUsedToAuthorize_cashierPinRejected() throws Exception {
        mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(new AuthorizeReq("654321"))))
                .andExpect(status().isOk()); // sanity: admin PIN works from a cashier-authenticated caller

        // A cashier's own PIN must not authorize — only an active ADMIN's PIN may.
        mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(new AuthorizeReq("111111"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getOrderById_includesAdjustmentHistoryAndOriginalTotal() throws Exception {
        var setup = setUpOrderWithExtra();
        var token = authorize("654321");
        adjust(setup.orderId(), "EXTRAS_ONLY", "DUPLICATE_ENTRY", null, token).andExpect(status().isCreated());

        mockMvc.perform(get("/orders/" + setup.orderId()).header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalTotal").value(62.00))
                .andExpect(jsonPath("$.total").value(50.00))
                .andExpect(jsonPath("$.adjustments.length()").value(1))
                .andExpect(jsonPath("$.adjustments[0].scope").value("EXTRAS_ONLY"))
                .andExpect(jsonPath("$.adjustments[0].reasonCode").value("DUPLICATE_ENTRY"))
                .andExpect(jsonPath("$.lines[0].extras[0].adjusted").value(true));
    }
}
