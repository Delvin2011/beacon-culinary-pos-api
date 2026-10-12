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
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.PosFlowHelper.DiscountAuth;
import com.beaconculinary.api.support.PosFlowHelper.DiscountReq;
import com.beaconculinary.api.support.PosFlowHelper.ExtraReq;
import com.beaconculinary.api.support.PosFlowHelper.LineReq;
import com.beaconculinary.api.support.PosFlowHelper.PaymentReq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Discount before payment: a manager-approved discount at checkout, so the customer pays less.
 * Replaces the retired after-payment discount (OrderDiscountIntegrationTests). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class SaleDiscountIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
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

    private PosFlowHelper pos;
    private String cashierToken;
    private long lunchId;
    private int mealCount;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window (12:00-14:30)
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        lunchId = pos.periodId("lunch");
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

    /** A daily option for a meal at the given price, with the given portions. */
    private long meal(String price, int portions) throws Exception {
        mealCount++;
        var componentId = pos.createComponent("Component " + mealCount, "5.00");
        return pos.createDailyOption(lunchId, pos.createMealCatalog("Meal " + mealCount, price, componentId), portions);
    }

    private long orderCount() {
        return orderRepository.count();
    }

    @Test
    void percentageDiscount_tenPercentOff100_customerPays90() throws Exception {
        var option = meal("100.00", 10);
        pos.openShift(cashierToken, "500.00");

        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("90.00")),
                        DiscountReq.percent("10", pos.authorizePin(cashierToken)), new LineReq(option))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preDiscountTotal").value(100.00))
                .andExpect(jsonPath("$.saleDiscount").value(10.00))
                .andExpect(jsonPath("$.originalTotal").value(90.00))
                .andExpect(jsonPath("$.total").value(90.00))
                .andExpect(jsonPath("$.payments[0].amount").value(90.00))
                .andExpect(jsonPath("$.discount.type").value("PERCENTAGE"))
                .andExpect(jsonPath("$.discount.value").value(10))
                .andExpect(jsonPath("$.discount.amount").value(10.00))
                .andExpect(jsonPath("$.discount.reasonCode").value("PROMOTION"))
                .andExpect(jsonPath("$.discount.authorizedBy.id").value(AuthTestHelper.adminId(mockMvc)))
                .andExpect(jsonPath("$.discount.authorizedBy.name").value("Canteen Admin"))
                .andExpect(jsonPath("$.adjustments").isEmpty());

        var row = jdbc.queryForMap("SELECT requested_by, authorized_by, amount FROM order_discounts");
        assertThat(((Number) row.get("requested_by")).longValue()).isEqualTo(AuthTestHelper.cashierAId(mockMvc));
        assertThat(((Number) row.get("authorized_by")).longValue()).isEqualTo(AuthTestHelper.adminId(mockMvc));
    }

    @Test
    void fixedDiscount_R10Off70_cashOnlyAndCashCardSplit() throws Exception {
        var extraStock = pos.createDailyComponentStock(lunchId, pos.createComponent("Chicken", "15.00"), 10);
        var option = meal("55.00", 10);
        pos.openShift(cashierToken, "500.00");
        var line = new LineReq(option, 1, List.of(new ExtraReq(extraStock, 1)));

        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("60.00")), DiscountReq.fixed("10.00", pos.authorizePin(cashierToken)), line)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preDiscountTotal").value(70.00))
                .andExpect(jsonPath("$.saleDiscount").value(10.00))
                .andExpect(jsonPath("$.originalTotal").value(60.00));
        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("40.00"), PaymentReq.card("20.00", "AUTH-9")),
                        DiscountReq.fixed("10.00", pos.authorizePin(cashierToken)), line)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(60.00));
    }

    @Test
    void paymentsMustEqualTheDiscountedTotal_andAFailedOrderLeavesTheTokenUsable() throws Exception {
        var option = meal("70.00", 10);
        pos.openShift(cashierToken, "500.00");
        var token = pos.authorizePin(cashierToken);

        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("70.00")), DiscountReq.fixed("10.00", token), new LineReq(option))
                .andExpect(status().isBadRequest());
        assertThat(orderCount()).isZero();

        // The rolled-back order didn't use the token up.
        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("60.00")), DiscountReq.fixed("10.00", token), new LineReq(option))
                .andExpect(status().isCreated());
        // Now it's spent.
        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("60.00")), DiscountReq.fixed("10.00", token), new LineReq(option))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_INVALID"));
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void stockConflictOnADiscountedOrder_rollsBack_tokenStillUsable() throws Exception {
        var option = meal("50.00", 1);
        pos.openShift(cashierToken, "500.00");
        var token = pos.authorizePin(cashierToken);

        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("90.00")), DiscountReq.fixed("10.00", token), new LineReq(option, 2, List.of()))
                .andExpect(status().isConflict());
        assertThat(orderCount()).isZero();
        assertThat(dailyMealOptionRepository.findById(option).orElseThrow().getPortionsRemaining()).isEqualTo(1);

        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("40.00")), DiscountReq.fixed("10.00", token), new LineReq(option))
                .andExpect(status().isCreated());
    }

    @Test
    void percentageRoundsHalfUp_and99point99PercentIsAccepted() throws Exception {
        var option33 = meal("33.33", 10);
        var option100 = meal("100.00", 10);
        pos.openShift(cashierToken, "500.00");

        // 33.33 x 15% = 4.9995 -> 5.00
        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("28.33")), DiscountReq.percent("15", pos.authorizePin(cashierToken)),
                        new LineReq(option33))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saleDiscount").value(5.00));
        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("0.01")), DiscountReq.percent("99.99", pos.authorizePin(cashierToken)),
                        new LineReq(option100))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saleDiscount").value(99.99))
                .andExpect(jsonPath("$.originalTotal").value(0.01));
    }

    @Test
    void invalidAndTooLargeDiscounts_areRefused_withoutUsingTheToken() throws Exception {
        var option = meal("100.00", 10);
        pos.openShift(cashierToken, "500.00");
        var token = pos.authorizePin(cashierToken);
        var auth = DiscountAuth.token(token);
        var pay = List.of(PaymentReq.cash("50.00"));
        var line = new LineReq(option);

        record Case(DiscountReq discount, String code) {
        }
        var cases = List.of(
                new Case(new DiscountReq("PERCENTAGE", new BigDecimal("100"), "PROMOTION", null, auth), "DISCOUNT_INVALID"),
                new Case(new DiscountReq("PERCENTAGE", BigDecimal.ZERO, "PROMOTION", null, auth), "DISCOUNT_INVALID"),
                new Case(new DiscountReq("FIXED_AMOUNT", new BigDecimal("-5"), "PROMOTION", null, auth), "DISCOUNT_INVALID"),
                new Case(new DiscountReq("FIXED_AMOUNT", null, "PROMOTION", null, auth), "DISCOUNT_INVALID"),
                new Case(new DiscountReq("BOGO", new BigDecimal("10"), "PROMOTION", null, auth), "DISCOUNT_INVALID"),
                new Case(new DiscountReq("FIXED_AMOUNT", new BigDecimal("10"), "BIRTHDAY", null, auth), "DISCOUNT_INVALID"),
                new Case(new DiscountReq("FIXED_AMOUNT", new BigDecimal("10"), "OTHER", null, auth), "DISCOUNT_INVALID"),
                new Case(new DiscountReq("FIXED_AMOUNT", new BigDecimal("10"), "STAFF", null, null), "DISCOUNT_INVALID"),
                new Case(new DiscountReq("FIXED_AMOUNT", new BigDecimal("10"), "STAFF", null, new DiscountAuth(token, "session")), "DISCOUNT_INVALID"),
                new Case(new DiscountReq("FIXED_AMOUNT", new BigDecimal("100.00"), "STAFF", null, auth), "DISCOUNT_TOO_LARGE"),
                new Case(new DiscountReq("FIXED_AMOUNT", new BigDecimal("150.00"), "STAFF", null, auth), "DISCOUNT_TOO_LARGE"));
        for (var c : cases) {
            pos.submitOrder(cashierToken, pay, c.discount(), line)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(c.code()));
        }
        assertThat(orderCount()).isZero();

        // OTHER with a note is fine, and the token survived every refusal above.
        pos.submitOrder(cashierToken, pay, new DiscountReq("FIXED_AMOUNT", new BigDecimal("50.00"), "OTHER", "Birthday treat", auth), line)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discount.note").value("Birthday treat"));
    }

    @Test
    void badOrExpiredAuthorization_is401_andNoOrderIsCreated() throws Exception {
        var option = meal("100.00", 10);
        pos.openShift(cashierToken, "500.00");
        var pay = List.of(PaymentReq.cash("90.00"));

        pos.submitOrder(cashierToken, pay, DiscountReq.fixed("10.00", "not-a-real-token"), new LineReq(option))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_INVALID"));
        pos.submitOrder(cashierToken, pay, new DiscountReq("FIXED_AMOUNT", new BigDecimal("10"), "STAFF", null,
                        DiscountAuth.session("not-a-session")), new LineReq(option))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_INVALID"));
        assertThat(orderCount()).isZero();
    }

    @Test
    void sessionToken_authorizesSeveralOrders_untilItExpires() throws Exception {
        var option = meal("100.00", 10);
        pos.openShift(cashierToken, "500.00");
        var session = pos.authorizeSession(cashierToken);
        var discount = new DiscountReq("FIXED_AMOUNT", new BigDecimal("10.00"), "STAFF", null, DiscountAuth.session(session));
        var pay = List.of(PaymentReq.cash("90.00"));

        pos.submitOrder(cashierToken, pay, discount, new LineReq(option)).andExpect(status().isCreated());
        pos.submitOrder(cashierToken, pay, discount, new LineReq(option)).andExpect(status().isCreated());

        clock.setTime(LocalTime.of(12, 36)); // the session was opened at 12:30 and lasts 5 minutes
        pos.submitOrder(cashierToken, pay, discount, new LineReq(option))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_INVALID"));
        assertThat(orderCount()).isEqualTo(2);
    }

    @Test
    void discountedCashOrder_cashUpCountsWhatWasPaid() throws Exception {
        var option = meal("70.00", 10);
        var shiftId = pos.openShift(cashierToken, "500.00");

        pos.submitOrder(cashierToken, List.of(PaymentReq.cash("60.00")), DiscountReq.fixed("10.00", pos.authorizePin(cashierToken)),
                        new LineReq(option))
                .andExpect(status().isCreated());

        // A discount at sale is not paid out: expected cash is float + the R60 actually taken.
        mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(jsonPath("$.cashSalesTotal").value(60.00))
                .andExpect(jsonPath("$.adjustmentsTotal").value(0))
                .andExpect(jsonPath("$.expectedCash").value(560.00));
    }

    @Test
    void voidingADiscountedPendingOrder_paysBackWhatWasPaid_inTheSameShiftOrALaterOne() throws Exception {
        var option = meal("70.00", 10);
        var shiftA = pos.openShift(cashierToken, "500.00");
        var first = PosFlowHelper.json(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("60.00")),
                DiscountReq.fixed("10.00", pos.authorizePin(cashierToken)), new LineReq(option))).get("id").asLong();
        var second = PosFlowHelper.json(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("60.00")),
                DiscountReq.fixed("10.00", pos.authorizePin(cashierToken)), new LineReq(option))).get("id").asLong();

        pos.adjustWith(cashierToken, first, "WHOLE_ORDER", null, pos.authorizePin(cashierToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.adjustments[0].amount").value(60.00))
                .andExpect(jsonPath("$.adjustments[0].refundMethod").value("CASH"));
        // Shift A: 500 + 120 taken - 60 paid back.
        pos.closeShift(cashierToken, shiftA, "560.00").andExpect(status().isOk());

        var shiftB = pos.openShift(cashierToken, "200.00");
        pos.adjustWith(cashierToken, second, "WHOLE_ORDER", null, pos.authorizePin(cashierToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adjustments[0].amount").value(60.00));
        mockMvc.perform(get("/shifts/" + shiftB + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(jsonPath("$.expectedCash").value(140.00));
    }

    @Test
    void refundingOrRemovingExtrasFromADiscountedOrder_isRefused_beforeTheTokenIsUsed() throws Exception {
        var extraStock = pos.createDailyComponentStock(lunchId, pos.createComponent("Chicken", "15.00"), 10);
        var option = meal("55.00", 10);
        pos.openShift(cashierToken, "500.00");
        var line = new LineReq(option, 1, List.of(new ExtraReq(extraStock, 1)));
        var discounted = PosFlowHelper.json(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("60.00")),
                DiscountReq.fixed("10.00", pos.authorizePin(cashierToken)), line)).get("id").asLong();
        var plain = pos.placeCashOrder(cashierToken, "70.00", line);
        var token = pos.authorizePin(cashierToken);

        // Still PENDING: removing extras is a partial refund.
        pos.adjustWith(cashierToken, discounted, "EXTRAS_ONLY", null, token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCOUNTED_ORDER_NOT_REFUNDABLE"));

        // Past the kitchen: a whole-order refund.
        pos.markDone(discounted);
        pos.adjustWith(cashierToken, discounted, "WHOLE_ORDER", null, token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCOUNTED_ORDER_NOT_REFUNDABLE"));
        assertThat(orderAdjustmentRepository.count()).isZero();

        // The same token still works for an order without a discount.
        pos.adjustWith(cashierToken, plain, "WHOLE_ORDER", null, token).andExpect(status().isCreated());
    }

    @Test
    void afterPaymentDiscount_isRetired_andWritesNothing() throws Exception {
        var option = meal("100.00", 10);
        pos.openShift(cashierToken, "500.00");
        var orderId = pos.placeCashOrder(cashierToken, "100.00", new LineReq(option));
        var token = pos.authorizePin(cashierToken);

        pos.adjustWith(cashierToken, orderId, null, "DISCOUNT", token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCOUNT_AFTER_PAYMENT_RETIRED"));
        assertThat(orderAdjustmentRepository.count()).isZero();
        assertThat(orderRepository.findById(orderId).orElseThrow().getTotal()).isEqualByComparingTo("100.00");

        // The token wasn't consumed; void still works.
        pos.adjustWith(cashierToken, orderId, "WHOLE_ORDER", null, token).andExpect(status().isCreated());
    }

    @Test
    void readEndpoints_returnTheDiscountFields_andPlainOrdersReadAsUndiscounted() throws Exception {
        var option = meal("100.00", 10);
        pos.openShift(cashierToken, "500.00");
        var discounted = PosFlowHelper.json(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("90.00")),
                DiscountReq.fixed("10.00", pos.authorizePin(cashierToken)), new LineReq(option))).get("id").asLong();
        var plain = pos.placeCashOrder(cashierToken, "100.00", new LineReq(option));

        mockMvc.perform(get("/orders/" + discounted).header("Authorization", "Bearer " + cashierToken))
                .andExpect(jsonPath("$.saleDiscount").value(10.00))
                .andExpect(jsonPath("$.discount.reasonCode").value("MANAGER_GOODWILL"))
                .andExpect(jsonPath("$.discount.authorizedBy.name").value("Canteen Admin"));
        mockMvc.perform(get("/orders/" + plain).header("Authorization", "Bearer " + cashierToken))
                .andExpect(jsonPath("$.preDiscountTotal").value(100.00))
                .andExpect(jsonPath("$.originalTotal").value(100.00))
                .andExpect(jsonPath("$.saleDiscount").value(0))
                .andExpect(jsonPath("$.discount").value(nullValue()));
        mockMvc.perform(get("/orders/today").header("Authorization", "Bearer " + cashierToken))
                .andExpect(jsonPath("$[1].id").value(discounted))
                .andExpect(jsonPath("$[1].discount.amount").value(10.00))
                .andExpect(jsonPath("$[0].discount").value(nullValue()));
    }
}
