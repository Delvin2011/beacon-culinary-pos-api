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
import com.beaconculinary.api.support.PosFlowHelper.ComplimentaryReq;
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

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Complimentary meals: a manager-approved order with no payment. It goes to the kitchen, gets a
 * daily order number and uses stock like any order, but no money moves. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class ComplimentaryOrderIntegrationTests {
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

    private long meal(String price, int portions) throws Exception {
        mealCount++;
        var componentId = pos.createComponent("Component " + mealCount, "5.00");
        return pos.createDailyOption(lunchId, pos.createMealCatalog("Meal " + mealCount, price, componentId), portions);
    }

    private long extraStock(String price, int buffer) throws Exception {
        mealCount++;
        return pos.createDailyComponentStock(lunchId, pos.createComponent("Extra " + mealCount, price), buffer);
    }

    private int portionsLeft(long optionId) {
        return dailyMealOptionRepository.findById(optionId).orElseThrow().getPortionsRemaining();
    }

    private int bufferLeft(long stockId) {
        return dailyComponentStockRepository.findById(stockId).orElseThrow().getBufferRemaining();
    }

    private long id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return PosFlowHelper.json(result.andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void complimentaryOrder_noPayment_listValueKept_numberedAndOnTheKitchenDisplay() throws Exception {
        var option = meal("35.00", 10);
        var salad = extraStock("10.00", 10);
        var shiftId = pos.openShift(cashierToken, "500.00");
        var line = new LineReq(option, 1, List.of(new ExtraReq(salad, 1)));

        pos.placeCashOrder(cashierToken, "35.00", new LineReq(option));
        var orderId = id(pos.submitComplimentary(cashierToken,
                        new ComplimentaryReq("STAFF_MEAL", "Kitchen team lunch", DiscountAuth.token(pos.authorizePin(cashierToken))), line)
                .andExpect(jsonPath("$.orderKind").value("COMPLIMENTARY"))
                .andExpect(jsonPath("$.orderNumber").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.preDiscountTotal").value(45.00))
                .andExpect(jsonPath("$.saleDiscount").value(0))
                .andExpect(jsonPath("$.originalTotal").value(0))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.payments").isEmpty())
                .andExpect(jsonPath("$.discount").value(nullValue()))
                .andExpect(jsonPath("$.complimentary.reasonCode").value("STAFF_MEAL"))
                .andExpect(jsonPath("$.complimentary.note").value("Kitchen team lunch"))
                .andExpect(jsonPath("$.complimentary.authorizedBy.id").value(AuthTestHelper.adminId(mockMvc))));

        var row = jdbc.queryForMap("SELECT requested_by, authorized_by, reason_code FROM order_complimentaries WHERE order_id = ?", orderId);
        assertThat(((Number) row.get("requested_by")).longValue()).isEqualTo(AuthTestHelper.cashierAId(mockMvc));
        assertThat(((Number) row.get("authorized_by")).longValue()).isEqualTo(AuthTestHelper.adminId(mockMvc));
        assertThat(portionsLeft(option)).isEqualTo(8);
        assertThat(bufferLeft(salad)).isEqualTo(9);

        // The kitchen sees it like any order; reads return the kind and the details.
        mockMvc.perform(get("/kitchen/orders").header("Authorization", "Bearer " + AuthTestHelper.loginAsKitchen(mockMvc)))
                .andExpect(jsonPath("$[*].orderId", hasItem((int) orderId)));
        mockMvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + cashierToken))
                .andExpect(jsonPath("$.orderKind").value("COMPLIMENTARY"))
                .andExpect(jsonPath("$.complimentary.reasonCode").value("STAFF_MEAL"));
        mockMvc.perform(get("/orders/today").header("Authorization", "Bearer " + cashierToken))
                .andExpect(jsonPath("$[0].orderKind").value("COMPLIMENTARY"))
                .andExpect(jsonPath("$[1].orderKind").value("SALE"))
                .andExpect(jsonPath("$[1].complimentary").value(nullValue()));

        // No money moved: cash-up counts only the R35 sale.
        mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(jsonPath("$.cashSalesTotal").value(35.00))
                .andExpect(jsonPath("$.expectedCash").value(535.00));
    }

    @Test
    void everyReasonCode_isAccepted_otherWithANote() throws Exception {
        var option = meal("35.00", 10);
        pos.openShift(cashierToken, "500.00");
        for (var reason : List.of("STAFF_MEAL", "MANAGEMENT", "GUEST", "TASTING", "OTHER")) {
            var note = reason.equals("OTHER") ? "Supplier visit" : null;
            pos.submitComplimentary(cashierToken, new ComplimentaryReq(reason, note, DiscountAuth.token(pos.authorizePin(cashierToken))),
                            new LineReq(option))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.complimentary.reasonCode").value(reason));
        }
        assertThat(orderRepository.count()).isEqualTo(5);
    }

    @Test
    void invalidComplimentary_is400_nothingWritten_tokenStillUsable() throws Exception {
        var option = meal("35.00", 10);
        pos.openShift(cashierToken, "500.00");
        var token = pos.authorizePin(cashierToken);
        var auth = DiscountAuth.token(token);

        record Case(ComplimentaryReq complimentary, List<PaymentReq> payments, DiscountReq discount) {
        }
        var cases = List.of(
                new Case(new ComplimentaryReq("STAFF_MEAL", null, auth), null, DiscountReq.fixed("5.00", token)),
                new Case(new ComplimentaryReq("STAFF_MEAL", null, auth), List.of(PaymentReq.cash("35.00")), null),
                new Case(new ComplimentaryReq("BIRTHDAY", null, auth), null, null),
                new Case(new ComplimentaryReq(null, null, auth), null, null),
                new Case(new ComplimentaryReq("OTHER", " ", auth), null, null),
                new Case(new ComplimentaryReq("STAFF_MEAL", null, new DiscountAuth(token, "a-session")), null, null),
                new Case(new ComplimentaryReq("STAFF_MEAL", null, null), null, null));
        for (var c : cases) {
            pos.submitComplimentary(cashierToken, c.complimentary(), c.payments(), c.discount(), new LineReq(option))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMPLIMENTARY_INVALID"));
        }
        assertThat(orderRepository.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_complimentaries", Long.class)).isZero();
        assertThat(portionsLeft(option)).isEqualTo(10);

        pos.submitComplimentary(cashierToken, ComplimentaryReq.staffMeal(token), new LineReq(option)).andExpect(status().isCreated());
    }

    @Test
    void badToken_is401_stockConflictLeavesTokenUsable_sessionAuthorizesSeveral() throws Exception {
        var option = meal("35.00", 1);
        pos.openShift(cashierToken, "500.00");

        pos.submitComplimentary(cashierToken, ComplimentaryReq.staffMeal("not-a-real-token"), new LineReq(option))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_INVALID"));

        var token = pos.authorizePin(cashierToken);
        pos.submitComplimentary(cashierToken, ComplimentaryReq.staffMeal(token), new LineReq(option, 2, List.of()))
                .andExpect(status().isConflict());
        assertThat(orderRepository.count()).isZero();
        pos.submitComplimentary(cashierToken, ComplimentaryReq.staffMeal(token), new LineReq(option)).andExpect(status().isCreated());
        // Now spent.
        var another = meal("35.00", 5);
        pos.submitComplimentary(cashierToken, ComplimentaryReq.staffMeal(token), new LineReq(another))
                .andExpect(status().isUnauthorized());

        var session = new ComplimentaryReq("TASTING", null, DiscountAuth.session(pos.authorizeSession(cashierToken)));
        pos.submitComplimentary(cashierToken, session, new LineReq(another)).andExpect(status().isCreated());
        pos.submitComplimentary(cashierToken, session, new LineReq(another)).andExpect(status().isCreated());
        assertThat(orderRepository.count()).isEqualTo(3);
    }

    @Test
    void voidWhilePending_restoresStock_movesNoMoney() throws Exception {
        var option = meal("35.00", 10);
        var salad = extraStock("10.00", 10);
        var shiftId = pos.openShift(cashierToken, "500.00");
        var orderId = id(pos.submitComplimentary(cashierToken, ComplimentaryReq.staffMeal(pos.authorizePin(cashierToken)),
                new LineReq(option, 1, List.of(new ExtraReq(salad, 1)))));

        pos.adjustWith(cashierToken, orderId, "WHOLE_ORDER", null, pos.authorizePin(cashierToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.adjustments[0].action").value("VOID"))
                .andExpect(jsonPath("$.adjustments[0].amount").value(0))
                .andExpect(jsonPath("$.adjustments[0].refundMethod").value("CASH"));
        assertThat(portionsLeft(option)).isEqualTo(10);
        assertThat(bufferLeft(salad)).isEqualTo(10);

        mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(jsonPath("$.cashSalesTotal").value(0))
                .andExpect(jsonPath("$.expectedCash").value(500.00));
    }

    @Test
    void refundOrRemoveExtras_is400_beforeTheTokenIsUsed() throws Exception {
        var option = meal("35.00", 10);
        var salad = extraStock("10.00", 10);
        pos.openShift(cashierToken, "500.00");
        var line = new LineReq(option, 1, List.of(new ExtraReq(salad, 1)));
        var pending = id(pos.submitComplimentary(cashierToken, ComplimentaryReq.staffMeal(pos.authorizePin(cashierToken)), line));
        var done = id(pos.submitComplimentary(cashierToken, ComplimentaryReq.staffMeal(pos.authorizePin(cashierToken)), line));
        pos.markDone(done);

        var token = pos.authorizePin(cashierToken);
        pos.adjustWith(cashierToken, pending, "EXTRAS_ONLY", null, token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMPLIMENTARY_ORDER_NOT_REFUNDABLE"));
        pos.adjustWith(cashierToken, done, "WHOLE_ORDER", null, token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMPLIMENTARY_ORDER_NOT_REFUNDABLE"));
        pos.adjustWith(cashierToken, done, "EXTRAS_ONLY", null, token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMPLIMENTARY_ORDER_NOT_REFUNDABLE"));
        assertThat(orderAdjustmentRepository.count()).isZero();

        // The refused requests didn't use the token: it still voids the pending order.
        pos.adjustWith(cashierToken, pending, "WHOLE_ORDER", null, token).andExpect(status().isCreated());
    }

    @Test
    void saleWithoutPayments_isStillRefusedAsBefore() throws Exception {
        var option = meal("35.00", 10);
        pos.openShift(cashierToken, "500.00");
        pos.submitOrder(cashierToken, List.of(), new LineReq(option))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payments").value("payments cannot be empty"));
        assertThat(orderRepository.count()).isZero();
    }
}
