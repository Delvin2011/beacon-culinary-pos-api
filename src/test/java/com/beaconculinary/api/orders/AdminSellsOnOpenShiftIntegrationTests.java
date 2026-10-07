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
import com.beaconculinary.api.support.PosFlowHelper.LineReq;
import com.beaconculinary.api.support.PosFlowHelper.PaymentReq;
import com.beaconculinary.api.users.UserRepository;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POS Oversight Part B — an ADMIN sells on whichever shift holds the till (B6/B7). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class AdminSellsOnOpenShiftIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
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
    private String adminToken;
    private String cashierToken;
    private String cashierBToken;
    private long optionId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window (12:00-14:30)
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        cashierBToken = AuthTestHelper.loginWithPin(mockMvc, AuthTestHelper.cashierBId(mockMvc), "654321");
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var mealId = pos.createMealCatalog("Meal", "50.00", pos.createComponent("Beef", "15.00"));
        optionId = pos.createDailyOption(pos.periodId("lunch"), mealId, 200);
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

    private long orderColumn(long orderId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM orders WHERE id = ?", Long.class, orderId);
    }

    private BigDecimal expectedCash(long shiftId) throws Exception {
        return PosFlowHelper.json(mockMvc.perform(get("/shifts/" + shiftId + "/summary")
                .header("Authorization", "Bearer " + adminToken))).get("expectedCash").decimalValue();
    }

    @Test
    void adminCashSaleOnCashierShift_belongsToCashiersDrawer_andCloseOutIncludesIt() throws Exception {
        var shiftId = pos.openShift(cashierToken, "500.00");

        var orderId = pos.placeCashOrder(adminToken, "50.00", new LineReq(optionId));

        assertThat(orderColumn(orderId, "shift_id")).isEqualTo(shiftId);
        assertThat(orderColumn(orderId, "cashier_id")).isEqualTo(AuthTestHelper.adminId(mockMvc));
        assertThat(expectedCash(shiftId)).isEqualByComparingTo("550.00");

        // The cashier counts the admin's sale in the drawer and closes with zero variance.
        pos.closeShift(cashierToken, shiftId, "550.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedCash").value(550.00))
                .andExpect(jsonPath("$.variance").value(0));

        mockMvc.perform(get("/admin/shifts/" + shiftId + "/activity").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.orders[0].rungBy.role").value("ADMIN"))
                .andExpect(jsonPath("$.shift.owner.role").value("CASHIER"));
    }

    @Test
    void adminSplitCashCardOrder_onlyCashPortionInExpectedCash() throws Exception {
        var shiftId = pos.openShift(cashierToken, "500.00");

        pos.submitOrder(adminToken, List.of(PaymentReq.cash("30.00"), PaymentReq.card("20.00", "AUTH-1")), new LineReq(optionId))
                .andExpect(status().isCreated());

        assertThat(expectedCash(shiftId)).isEqualByComparingTo("530.00");
    }

    @Test
    void cashierOnAnotherCashiersShift_gets409TillInUse_namingTheOwner() throws Exception {
        pos.openShift(cashierToken, "500.00");
        var ownerName = userRepository.findById(AuthTestHelper.cashierAId(mockMvc)).orElseThrow().getName();

        pos.submitOrder(cashierBToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TILL_IN_USE"))
                .andExpect(jsonPath("$.error").value("Till in use by " + ownerName + "."));

        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void noOpenShift_returns409NoOpenShift_forAdminAndCashier() throws Exception {
        for (var token : List.of(adminToken, cashierToken)) {
            pos.submitOrder(token, List.of(PaymentReq.cash("50.00")), new LineReq(optionId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("NO_OPEN_SHIFT"));
        }

        // An admin still opens a shift first (unchanged), then sells on it as its owner.
        pos.openShift(adminToken, "100.00");
        pos.placeCashOrder(adminToken, "50.00", new LineReq(optionId));
    }

    @Test
    void getOpenShift_reportsOwnerAndOwnedByCaller() throws Exception {
        mockMvc.perform(get("/shifts/open").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        var shiftId = pos.openShift(cashierToken, "500.00");
        var cashierAId = AuthTestHelper.cashierAId(mockMvc);

        mockMvc.perform(get("/shifts/open").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftId").value(shiftId))
                .andExpect(jsonPath("$.owner.id").value(cashierAId))
                .andExpect(jsonPath("$.owner.role").value("CASHIER"))
                .andExpect(jsonPath("$.ownedByCaller").value(true))
                .andExpect(jsonPath("$.openingFloat").value(500.00))
                .andExpect(jsonPath("$.openedAt").value(org.hamcrest.Matchers.endsWith("Z")));
        for (var token : List.of(adminToken, cashierBToken)) {
            mockMvc.perform(get("/shifts/open").header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.owner.id").value(cashierAId))
                    .andExpect(jsonPath("$.ownedByCaller").value(false));
        }

        pos.closeShift(cashierToken, shiftId, "500.00").andExpect(status().isOk());
        pos.openShift(adminToken, "100.00");
        mockMvc.perform(get("/shifts/open").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.owner.role").value("ADMIN"))
                .andExpect(jsonPath("$.ownedByCaller").value(true));
    }

    @Test
    void adminVoidRefundDiscountOnCashierShift_attributedToThatShift() throws Exception {
        var shiftId = pos.openShift(cashierToken, "500.00");
        var toVoid = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        var toRefund = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        var toDiscount = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        pos.markDone(toRefund);

        pos.adjust(adminToken, toVoid, "WHOLE_ORDER");
        pos.adjust(adminToken, toRefund, "WHOLE_ORDER");
        pos.discountFixed(adminToken, toDiscount, "10.00");

        var adjustmentShiftIds = jdbcTemplate.queryForList("SELECT shift_id FROM order_adjustments", Long.class);
        assertThat(adjustmentShiftIds).hasSize(3).containsOnly(shiftId);
        // 500 + 150 cash sales - (50 void + 50 refund + 10 discount)
        assertThat(expectedCash(shiftId)).isEqualByComparingTo("540.00");
    }

    @Test
    void adminClosingCashiersShift_recordsAdminAsClosedBy() throws Exception {
        var shiftId = pos.openShift(cashierToken, "500.00");

        pos.closeShift(adminToken, shiftId, "500.00").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT closed_by FROM shifts WHERE id = ?", Long.class, shiftId))
                .isEqualTo(AuthTestHelper.adminId(mockMvc));
    }

    @Test
    void shiftClosedWhileAdminIsMidOrder_nextSubmitGetsNoOpenShift() throws Exception {
        var shiftId = pos.openShift(cashierToken, "500.00");
        pos.closeShift(cashierToken, shiftId, "500.00").andExpect(status().isOk());

        pos.submitOrder(adminToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_OPEN_SHIFT"));
    }

    /**
     * B3: a close and an admin order racing on the same shift. Whichever wins, the order must be
     * either inside the closed shift's snapshot or rejected — never attached to a shift whose
     * snapshot excludes it. Repeated because a single run may not interleave.
     */
    @Test
    void concurrentCloseAndAdminOrder_orderIsInSnapshotOrRejected_neverOrphaned() throws Exception {
        var outcomes = new java.util.HashSet<Integer>();

        for (var run = 0; run < 15; run++) {
            var shiftId = pos.openShift(cashierToken, "500.00");
            var varianceToken = pos.authorizePin(cashierToken);
            var start = new CountDownLatch(1);

            Callable<Integer> close = () -> {
                start.await();
                return pos.closeShift(cashierToken, shiftId, "500.00", varianceToken).andReturn().getResponse().getStatus();
            };
            Callable<Integer> order = () -> {
                start.await();
                return pos.submitOrder(adminToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId))
                        .andReturn().getResponse().getStatus();
            };

            var pool = Executors.newFixedThreadPool(2);
            try {
                var closeResult = pool.submit(close);
                var orderResult = pool.submit(order);
                start.countDown();
                assertThat(closeResult.get()).as("close status, run %d", run).isEqualTo(200);
                var orderStatus = orderResult.get();
                assertThat(orderStatus).as("order status, run %d", run).isIn(201, 409);
                outcomes.add(orderStatus);
            } finally {
                pool.shutdown();
            }

            var shift = shiftRepository.findById(shiftId).orElseThrow();
            var cashOnShift = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(p.amount), 0) FROM order_payments p JOIN orders o ON o.id = p.order_id "
                            + "WHERE o.shift_id = ? AND p.method = 'CASH'", BigDecimal.class, shiftId);
            assertThat(shift.getCashSalesAtClose()).as("snapshot vs orders on shift, run %d", run)
                    .isEqualByComparingTo(cashOnShift);
            assertThat(shift.getExpectedCash()).isEqualByComparingTo(new BigDecimal("500.00").add(cashOnShift));
        }

        System.out.println("concurrentCloseAndAdminOrder outcomes (order status codes seen): " + outcomes);
    }
}
