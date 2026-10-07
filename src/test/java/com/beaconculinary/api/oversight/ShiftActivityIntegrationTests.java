package com.beaconculinary.api.oversight;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.PosFlowHelper;
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
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POS Oversight Part A — GET /admin/shifts and GET /admin/shifts/{id}/activity (A6/A7). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class ShiftActivityIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
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
    private long lunchId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window (12:00-14:30)
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        cashierBToken = AuthTestHelper.loginWithPin(mockMvc, AuthTestHelper.cashierBId(mockMvc), "654321");
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        lunchId = pos.periodId("lunch");
    }

    @AfterEach
    void tearDown() {
        clock.reset();
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

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(get("/admin/shifts" + query).header("Authorization", "Bearer " + adminToken));
    }

    private ResultActions activity(long shiftId) throws Exception {
        return mockMvc.perform(get("/admin/shifts/" + shiftId + "/activity").header("Authorization", "Bearer " + adminToken));
    }

    private LocalDate businessToday() {
        return LocalDate.ofInstant(clock.instant(), ClockConfig.BUSINESS_ZONE);
    }

    /** The UTC wall-clock value stored in opened_at for a Johannesburg local date and time. */
    private static LocalDateTime utc(LocalDate businessDay, int hour, int minute) {
        return businessDay.atTime(hour, minute).atZone(ClockConfig.BUSINESS_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private void setOpenedAt(long shiftId, LocalDateTime openedAtUtc) {
        jdbcTemplate.update("UPDATE shifts SET opened_at = ? WHERE id = ?", Timestamp.valueOf(openedAtUtc), shiftId);
    }

    private long openAndCloseEmpty(String token, LocalDateTime openedAtUtc) throws Exception {
        var shiftId = pos.openShift(token, "100.00");
        pos.closeShift(token, shiftId, "100.00").andExpect(status().isOk());
        setOpenedAt(shiftId, openedAtUtc);
        return shiftId;
    }

    @Test
    void nonAdminRoles_get403() throws Exception {
        var tokens = List.of(cashierToken, AuthTestHelper.loginAsKitchen(mockMvc), AuthTestHelper.loginAsStockAdmin(mockMvc));
        for (var token : tokens) {
            mockMvc.perform(get("/admin/shifts").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/admin/shifts/1/activity").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void displayStatus_followsJohannesburgCalendarDay_includingJustAfterLocalMidnight() throws Exception {
        var today = businessToday();
        clock.setInstant(today.atTime(10, 0).atZone(ClockConfig.BUSINESS_ZONE).toInstant());
        var shiftId = pos.openShift(cashierToken, "200.00");

        // 00:30 Johannesburg = 22:30 UTC the previous day: still today locally, so ON_SHIFT.
        setOpenedAt(shiftId, utc(today, 0, 30));
        list("?status=ON_SHIFT").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].shiftId", contains((int) shiftId)))
                .andExpect(jsonPath("$.content[0].displayStatus").value("ON_SHIFT"));
        list("?from=" + today + "&to=" + today)
                .andExpect(jsonPath("$.content[*].shiftId", contains((int) shiftId)));
        list("?status=OVERDUE").andExpect(jsonPath("$.totalElements").value(0));

        // 23:50 Johannesburg yesterday: a forgotten shift from an earlier day.
        setOpenedAt(shiftId, utc(today.minusDays(1), 23, 50));
        list("?status=OVERDUE")
                .andExpect(jsonPath("$.content[*].shiftId", contains((int) shiftId)))
                .andExpect(jsonPath("$.content[0].displayStatus").value("OVERDUE"));
        activity(shiftId).andExpect(jsonPath("$.shift.displayStatus").value("OVERDUE"));

        pos.closeShift(adminToken, shiftId, "200.00").andExpect(status().isOk());
        list("").andExpect(jsonPath("$.content[0].displayStatus").value("CLOSED"));
    }

    @Test
    void filters_workAloneAndCombined_newestFirst_defaultLastSevenDays() throws Exception {
        var today = businessToday();
        var adminShift = openAndCloseEmpty(adminToken, utc(today.minusDays(10), 9, 0));
        var cashierAShift = openAndCloseEmpty(cashierToken, utc(today.minusDays(3), 9, 0));
        var cashierBShift = pos.openShift(cashierBToken, "100.00");
        var adminId = AuthTestHelper.adminId(mockMvc);

        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].shiftId", contains((int) cashierBShift, (int) cashierAShift)))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.size").value(25));
        list("?ownerId=" + AuthTestHelper.cashierAId(mockMvc))
                .andExpect(jsonPath("$.content[*].shiftId", contains((int) cashierAShift)));
        list("?status=CLOSED")
                .andExpect(jsonPath("$.content[*].shiftId", contains((int) cashierAShift)));
        list("?status=ON_SHIFT")
                .andExpect(jsonPath("$.content[*].shiftId", contains((int) cashierBShift)));
        list("?from=" + today.minusDays(12) + "&to=" + today.minusDays(5))
                .andExpect(jsonPath("$.content[*].shiftId", contains((int) adminShift)))
                .andExpect(jsonPath("$.content[0].owner.role").value("ADMIN"))
                .andExpect(jsonPath("$.content[0].owner.id").value(adminId));
        list("?from=" + today.minusDays(12) + "&status=CLOSED&ownerId=" + adminId)
                .andExpect(jsonPath("$.content[*].shiftId", contains((int) adminShift)));
        list("?from=" + today.minusDays(12) + "&to=" + today)
                .andExpect(jsonPath("$.content[*].shiftId", contains((int) cashierBShift, (int) cashierAShift, (int) adminShift)));
        list("?from=" + today + "&to=" + today.minusDays(1)).andExpect(status().isBadRequest());
        list("?status=NOPE").andExpect(status().isBadRequest());
    }

    @Test
    void openShift_nullCloseFields_liveBreakdownMatchesSummary() throws Exception {
        var optionId = pos.createDailyOption(lunchId, pos.createMealCatalog("Meal", "50.00", pos.createComponent("Beef", "15.00")), 10);
        var shiftId = pos.openShift(cashierToken, "500.00");
        pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));

        list("").andExpect(jsonPath("$.content[0]").value(hasKey("closedAt")))
                .andExpect(jsonPath("$.content[0].closedAt").value(nullValue()))
                .andExpect(jsonPath("$.content[0].closingCash").value(nullValue()))
                .andExpect(jsonPath("$.content[0].variance").value(nullValue()))
                .andExpect(jsonPath("$.content[0].expectedCash").value(550.00))
                .andExpect(jsonPath("$.content[0].orderCount").value(1))
                .andExpect(jsonPath("$.content[0].owner.role").value("CASHIER"));

        var summary = PosFlowHelper.json(mockMvc.perform(get("/shifts/" + shiftId + "/summary")
                .header("Authorization", "Bearer " + cashierToken)));
        activity(shiftId).andExpect(status().isOk())
                .andExpect(jsonPath("$.shift.closedBy").value(nullValue()))
                .andExpect(jsonPath("$.expectedCashBreakdown.source").value("LIVE"))
                .andExpect(jsonPath("$.expectedCashBreakdown").value(hasKey("breakdownMismatch")))
                .andExpect(jsonPath("$.expectedCashBreakdown.breakdownMismatch").value(nullValue()))
                .andExpect(jsonPath("$.expectedCashBreakdown.expectedCash").value(summary.get("expectedCash").asDouble()));
    }

    @Test
    void crossShiftRefund_inLaterShiftsAdjustments_andEarlierShiftsOrdersOnly() throws Exception {
        var optionId = pos.createDailyOption(lunchId, pos.createMealCatalog("Meal", "50.00", pos.createComponent("Beef", "15.00")), 10);

        var shiftA = pos.openShift(cashierToken, "500.00");
        var orderId = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        pos.markDone(orderId);
        pos.closeShift(cashierToken, shiftA, "550.00").andExpect(status().isOk());

        var shiftB = pos.openShift(cashierToken, "200.00");
        pos.adjust(cashierToken, orderId, "WHOLE_ORDER"); // DONE -> REFUND, 50.00, processed in B

        activity(shiftA).andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].orderId").value(orderId))
                .andExpect(jsonPath("$.orders[0].status").value("REFUNDED"))
                .andExpect(jsonPath("$.adjustments").isEmpty())
                .andExpect(jsonPath("$.totals.refunds.count").value(0))
                .andExpect(jsonPath("$.expectedCashBreakdown.source").value("SNAPSHOT"))
                .andExpect(jsonPath("$.expectedCashBreakdown.expectedCash").value(550.00))
                .andExpect(jsonPath("$.expectedCashBreakdown.breakdownMismatch").value(false));

        activity(shiftB).andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").isEmpty())
                .andExpect(jsonPath("$.adjustments[0].orderId").value(orderId))
                .andExpect(jsonPath("$.adjustments[0].type").value("REFUND"))
                .andExpect(jsonPath("$.adjustments[0].amount").value(50.00))
                .andExpect(jsonPath("$.adjustments[0].refundMethod").value("CASH"))
                .andExpect(jsonPath("$.adjustments[0].crossShift").value(true))
                .andExpect(jsonPath("$.adjustments[0].orderSoldInShiftId").value(shiftA))
                .andExpect(jsonPath("$.totals.refunds.count").value(1))
                .andExpect(jsonPath("$.totals.refunds.amount").value(50.00))
                .andExpect(jsonPath("$.expectedCashBreakdown.source").value("LIVE"))
                .andExpect(jsonPath("$.expectedCashBreakdown.cashRefundsProcessed").value(50.00))
                .andExpect(jsonPath("$.expectedCashBreakdown.expectedCash").value(150.00));
    }

    @Test
    void flagsAndTotals_reconcileWithRows_andNoCardReferenceIsExposed() throws Exception {
        var chickenId = pos.createComponent("Chicken", "10.00");
        var optionId = pos.createDailyOption(lunchId, pos.createMealCatalog("Meal", "50.00", pos.createComponent("Beef", "15.00")), 10);
        var chickenStockId = pos.createDailyComponentStock(lunchId, chickenId, 5);
        var shiftId = pos.openShift(cashierToken, "500.00");

        var discounted = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        var extrasRemoved = pos.placeCashOrder(cashierToken, "60.00",
                new LineReq(optionId, 1, List.of(new ExtraReq(chickenStockId, 1))));
        var voided = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        pos.submitOrder(cashierToken, List.of(PaymentReq.card("50.00", "AUTH-SECRET-1")), new LineReq(optionId))
                .andExpect(status().isCreated());

        pos.discountFixed(cashierToken, discounted, "10.00");
        pos.markDone(extrasRemoved);
        pos.adjust(cashierToken, extrasRemoved, "EXTRAS_ONLY"); // REFUND of the 10.00 extra
        pos.adjust(cashierToken, voided, "WHOLE_ORDER");        // PENDING -> VOID, 50.00

        var body = activity(shiftId).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.orderCount").value(4))
                .andExpect(jsonPath("$.totals.salesByMethod.CASH").value(160.00))
                .andExpect(jsonPath("$.totals.salesByMethod.CARD").value(50.00))
                .andExpect(jsonPath("$.totals.salesByMethod.ACCOUNT").value(0))
                .andExpect(jsonPath("$.totals.voids.count").value(1))
                .andExpect(jsonPath("$.totals.voids.amount").value(50.00))
                .andExpect(jsonPath("$.totals.refunds.count").value(0))
                .andExpect(jsonPath("$.totals.discounts.count").value(1))
                .andExpect(jsonPath("$.totals.discounts.amount").value(10.00))
                .andExpect(jsonPath("$.totals.extrasRemoved.count").value(1))
                .andExpect(jsonPath("$.totals.extrasRemoved.amount").value(10.00))
                .andExpect(jsonPath("$.orders[0].orderId").value(discounted))
                .andExpect(jsonPath("$.orders[0].discounted").value(true))
                .andExpect(jsonPath("$.orders[0].extrasRemoved").value(false))
                .andExpect(jsonPath("$.orders[0].originalTotal").value(50.00))
                .andExpect(jsonPath("$.orders[0].total").value(40.00))
                .andExpect(jsonPath("$.orders[0].payments[0].amount").value(50.00))
                .andExpect(jsonPath("$.orders[1].extrasRemoved").value(true))
                .andExpect(jsonPath("$.orders[1].discounted").value(false))
                .andExpect(jsonPath("$.orders[2].status").value("VOIDED"))
                .andExpect(jsonPath("$.orders[3].payments[0].method").value("CARD"))
                .andExpect(jsonPath("$.adjustments[*].type", contains("DISCOUNT", "EXTRAS_REMOVED", "VOID")))
                .andExpect(jsonPath("$.adjustments[0].crossShift").value(false))
                .andExpect(jsonPath("$.expectedCashBreakdown.cashSales").value(160.00))
                .andExpect(jsonPath("$.expectedCashBreakdown.cashRefundsProcessed").value(70.00))
                .andExpect(jsonPath("$.expectedCashBreakdown.expectedCash").value(590.00))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("AUTH-SECRET-1").doesNotContainIgnoringCase("cardReference");
        assertThat(list("").andReturn().getResponse().getContentAsString()).doesNotContain("AUTH-SECRET-1");
    }

    @Test
    void close_storesSnapshotAndClosedBy_tamperedSnapshotIsFlagged_legacyShiftIsRecalculated() throws Exception {
        var optionId = pos.createDailyOption(lunchId, pos.createMealCatalog("Meal", "50.00", pos.createComponent("Beef", "15.00")), 10);
        var shiftId = pos.openShift(cashierToken, "500.00");
        pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        pos.closeShift(cashierToken, shiftId, "550.00").andExpect(status().isOk());

        var shift = shiftRepository.findById(shiftId).orElseThrow();
        assertThat(shift.getCashSalesAtClose()).isEqualByComparingTo("50.00");
        assertThat(shift.getCashRefundsAtClose()).isEqualByComparingTo("0.00");
        assertThat(shift.getExpectedCash()).isEqualByComparingTo(
                shift.getOpeningFloat().add(shift.getCashSalesAtClose()).subtract(shift.getCashRefundsAtClose()));
        assertThat(jdbcTemplate.queryForObject("SELECT closed_by FROM shifts WHERE id = ?", Long.class, shiftId))
                .isEqualTo(AuthTestHelper.cashierAId(mockMvc));

        activity(shiftId)
                .andExpect(jsonPath("$.shift.closedBy.id").value(AuthTestHelper.cashierAId(mockMvc)))
                .andExpect(jsonPath("$.shift.closingCash").value(550.00))
                .andExpect(jsonPath("$.shift.variance").value(0))
                .andExpect(jsonPath("$.expectedCashBreakdown.source").value("SNAPSHOT"))
                .andExpect(jsonPath("$.expectedCashBreakdown.breakdownMismatch").value(false))
                .andExpect(jsonPath("$.expectedCashBreakdown.recomputed").doesNotExist());

        jdbcTemplate.update("UPDATE shifts SET cash_sales_at_close = 55.00 WHERE id = ?", shiftId);
        activity(shiftId)
                .andExpect(jsonPath("$.expectedCashBreakdown.source").value("SNAPSHOT"))
                .andExpect(jsonPath("$.expectedCashBreakdown.cashSales").value(55.00))
                .andExpect(jsonPath("$.expectedCashBreakdown.breakdownMismatch").value(true))
                .andExpect(jsonPath("$.expectedCashBreakdown.recomputed.cashSales").value(50.00))
                .andExpect(jsonPath("$.expectedCashBreakdown.recomputed.expectedCash").value(550.00));

        jdbcTemplate.update("UPDATE shifts SET cash_sales_at_close = NULL, cash_refunds_at_close = NULL WHERE id = ?", shiftId);
        activity(shiftId)
                .andExpect(jsonPath("$.expectedCashBreakdown.source").value("RECALCULATED"))
                .andExpect(jsonPath("$.expectedCashBreakdown").value(hasKey("breakdownMismatch")))
                .andExpect(jsonPath("$.expectedCashBreakdown.breakdownMismatch").value(nullValue()))
                .andExpect(jsonPath("$.expectedCashBreakdown.storedExpectedCash").value(550.00))
                .andExpect(jsonPath("$.expectedCashBreakdown.expectedCash").value(550.00));
    }

    @Test
    void unknownShift_returns404() throws Exception {
        activity(Long.MAX_VALUE).andExpect(status().isNotFound());
    }
}
