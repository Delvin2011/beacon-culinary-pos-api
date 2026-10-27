package com.beaconculinary.api.reports;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.ReportFixture;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.beaconculinary.api.support.ReportFixture.MONDAY;
import static com.beaconculinary.api.support.ReportFixture.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reports R3 acceptance on the R1 fixture (with the discount and complimentary orders): X,
 * Cashier and Z readings, sign-off, Close Day, the Open Shift guard, Cash-up's sign-off
 * columns and Tender Summary's check list with reprint. Z numbering starts on Mon 5 Oct here;
 * the earlier-day rule is covered by {@link EndOfDayPreviousDayIntegrationTests}. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = "reports.close-day.first-trading-day=2026-10-05")
class EndOfDayIntegrationTests {
    private static final String MON = "2026-10-05";
    private static final String TUE = "2026-10-06";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private String adminToken;
    private ReportFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(TUESDAY.atTime(13, 0).atZone(ClockConfig.BUSINESS_ZONE).toInstant());
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        ReportFixture.clear(jdbc);
        fixture = ReportFixture.seed(jdbc, AuthTestHelper.cashierAId(mockMvc), AuthTestHelper.cashierBId(mockMvc),
                AuthTestHelper.adminId(mockMvc));
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        ReportFixture.clear(jdbc);
    }

    private ResultActions as(String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private ResultActions run(String key, String query) throws Exception {
        return as(adminToken, get("/admin/reports/" + key + "?" + query));
    }

    private ResultActions signOff(long shiftId) throws Exception {
        return as(adminToken, post("/admin/shifts/" + shiftId + "/signoff"));
    }

    private ResultActions closeDay(String day) throws Exception {
        return as(adminToken, post("/admin/days/" + day + "/close"));
    }

    /** The value of a key-value item: $.sections[key].items[label].value (as a one-element list). */
    private static String item(String section, String label) {
        return "$.sections[?(@.key == '" + section + "')].items[?(@.label == '" + label + "')].value";
    }

    private static String rows(String section, String column) {
        return "$.sections[?(@.key == '" + section + "')].rows[*]." + column;
    }

    // --- X Reading ----------------------------------------------------------------------------

    @Test
    void xReading_monday_matchesFixture() throws Exception {
        run("x-reading", "tradingDay=" + MON).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statementTitle").value("X Reading"))
                .andExpect(jsonPath("$.meta.business.name").value("Beacon Culinary"))
                .andExpect(jsonPath("$.meta.exportFormats", contains("xlsx")))
                .andExpect(jsonPath("$.columns").value(nullValue()))
                .andExpect(jsonPath(item("header", "Day status"), contains("Open")))
                .andExpect(jsonPath(item("sales", "Orders (sales)"), contains(7)))
                .andExpect(jsonPath(item("sales", "Voided/refunded orders"), contains(2)))
                .andExpect(jsonPath(item("sales", "Net orders"), contains(5)))
                .andExpect(jsonPath(item("sales", "Gross sales"), contains(570.0)))
                .andExpect(jsonPath(item("sales", "Discounts at sale"), contains(10.0)))
                .andExpect(jsonPath(item("sales", "Voids"), contains(50.0)))
                .andExpect(jsonPath(item("sales", "Refunds"), contains(90.0)))
                .andExpect(jsonPath(item("sales", "Extras removed"), contains(0.0)))
                .andExpect(jsonPath(item("sales", "Net sales"), contains(420.0)))
                .andExpect(jsonPath("$.sections[?(@.key == 'sales')].items[?(@.label == 'Net sales')].emphasis", contains("TOTAL")))
                .andExpect(jsonPath(item("sales", "Average order value"), contains(84.0)))
                .andExpect(jsonPath(item("complimentary", "Complimentary meals"), contains(1)))
                .andExpect(jsonPath(item("complimentary", "Value at list price"), contains(45.0)))
                .andExpect(jsonPath(rows("complimentaryByReason", "reason"), contains("Staff meal")))
                .andExpect(jsonPath(item("tender", "Cash"), contains(350.0)))
                .andExpect(jsonPath(item("tender", "Card"), contains(90.0)))
                .andExpect(jsonPath(item("tender", "Account"), contains(120.0)))
                .andExpect(jsonPath(item("tender", "Total collected"), contains(560.0)))
                .andExpect(jsonPath(item("paidOut", "Cash paid out"), contains(140.0)))
                .andExpect(jsonPath(item("paidOut", "Account credits"), contains(0.0)))
                .andExpect(jsonPath(item("cash", "Opening floats"), contains(500.0)))
                .andExpect(jsonPath(item("cash", "Net cash movement"), contains(210.0)))
                .andExpect(jsonPath(item("cash", "Expected cash"), contains(710.0)))
                .andExpect(jsonPath(item("cash", "Counted cash (closed shifts)"), contains(700.0)))
                .andExpect(jsonPath(item("cash", "Variance"), contains(-10.0)))
                .andExpect(jsonPath(rows("shifts", "shiftId"), contains((int) fixture.s1, (int) fixture.s2)))
                .andExpect(jsonPath(rows("shifts", "cashier"), contains("Canteen Cashier", "Canteen Cashier B")))
                .andExpect(jsonPath(rows("shifts", "openingFloat"), contains(200.0, 300.0)))
                .andExpect(jsonPath(rows("shifts", "cashSales"), contains(290.0, 60.0)))
                .andExpect(jsonPath(rows("shifts", "cashRefunds"), contains(50.0, 90.0)))
                .andExpect(jsonPath(rows("shifts", "expectedCash"), contains(440.0, 270.0)))
                .andExpect(jsonPath(rows("shifts", "countedCash"), contains(440.0, 260.0)))
                .andExpect(jsonPath(rows("shifts", "variance"), contains(0.0, -10.0)))
                .andExpect(jsonPath(rows("shifts", "signedOffBy"), contains("Not signed off", "Not signed off")))
                .andExpect(jsonPath("$.sections[?(@.key == 'shifts')].slipColumns[*]", contains("shiftId", "cashier", "variance")))
                .andExpect(jsonPath(rows("cashierReadingsVsX", "tender"), contains("Cash", "Card", "Account")))
                .andExpect(jsonPath(rows("cashierReadingsVsX", "cashierReadings"), contains(350.0, 90.0, 120.0)))
                .andExpect(jsonPath(rows("cashierReadingsVsX", "xReading"), contains(350.0, 90.0, 120.0)))
                .andExpect(jsonPath(rows("cashierReadingsVsX", "difference"), contains(0.0, 0.0, 0.0)))
                .andExpect(jsonPath("$.sections[?(@.key == 'cashierReadingsVsX')].totals.cashierReadings", contains(560.0)))
                .andExpect(jsonPath("$.sections[?(@.key == 'signatures')].labels[*]", contains("Manager")))
                .andExpect(jsonPath("$.checks[*].key", contains("cashier-readings-agree-with-x", "x-agrees-with-reports", "unsigned-shifts")))
                .andExpect(jsonPath("$.checks[*].status", contains("PASS", "PASS", "WARN")))
                .andExpect(jsonPath("$.checks[2].actual").value(2))
                .andExpect(jsonPath("$.actions[0].key").value("CLOSE_DAY"))
                .andExpect(jsonPath("$.actions[0].path").value("/admin/days/" + MON + "/close"))
                .andExpect(jsonPath("$.actions[0].enabled").value(false))
                .andExpect(jsonPath("$.actions[0].confirm").value(
                        "Close Mon 5 Oct 2026? This produces Z-0001 and you cannot open another shift for this day."))
                .andExpect(jsonPath("$.actions[0].disabledReasons", contains(
                        "Shift #" + fixture.s1 + " (Canteen Cashier) is not signed off.",
                        "Shift #" + fixture.s2 + " (Canteen Cashier B) is not signed off.")));
    }

    @Test
    void xReading_dayWithOnlyComplimentaryOrders() throws Exception {
        var wednesday = TUESDAY.plusDays(1);
        var adminId = AuthTestHelper.adminId(mockMvc);
        var shift = fixture.closedShift(adminId, "100.00", ReportFixture.utc(wednesday, 8, 0), ReportFixture.utc(wednesday, 15, 0),
                "100.00", "100.00", "0.00", null, null, adminId, "0.00", "0.00");
        fixture.complimentaryOrder(shift, adminId, wednesday, 1, 12, 0, "60.00", "COLLECTED", "GUEST", adminId);

        run("x-reading", "tradingDay=2026-10-07").andExpect(status().isOk())
                .andExpect(jsonPath(item("sales", "Orders (sales)"), contains(0)))
                .andExpect(jsonPath(item("sales", "Net sales"), contains(0.0)))
                .andExpect(jsonPath(item("tender", "Total collected"), contains(0.0)))
                .andExpect(jsonPath(item("complimentary", "Complimentary meals"), contains(1)))
                .andExpect(jsonPath(item("complimentary", "Value at list price"), contains(60.0)))
                .andExpect(jsonPath("$.checks[0].status").value("PASS"))
                .andExpect(jsonPath("$.checks[1].status").value("PASS"));
    }

    // --- Cashier Reading ----------------------------------------------------------------------

    @Test
    void cashierReading_s1() throws Exception {
        run("cashier-reading", "shiftId=" + fixture.s1).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.statementTitle").value("Cashier Reading"))
                .andExpect(jsonPath("$.meta.interim").value(false))
                .andExpect(jsonPath("$.meta.paramLabels.shiftId").value("Mon 5 Oct · Canteen Cashier · 07:00–11:00"))
                .andExpect(jsonPath(item("header", "Cashier"), contains("Canteen Cashier")))
                .andExpect(jsonPath(item("takings", "Orders (sales)"), contains(5)))
                .andExpect(jsonPath(item("takings", "Voided/refunded orders"), contains(2)))
                .andExpect(jsonPath(item("takings", "Gross sales"), contains(380.0)))
                .andExpect(jsonPath(item("takings", "Discounts at sale"), contains(0.0)))
                .andExpect(jsonPath(item("takings", "Reductions after payment on these orders"), contains(140.0)))
                .andExpect(jsonPath(item("takings", "Net sales"), contains(240.0)))
                .andExpect(jsonPath(item("takings", "Collected: cash"), contains(290.0)))
                .andExpect(jsonPath(item("takings", "Collected: card"), contains(90.0)))
                .andExpect(jsonPath(item("takings", "Collected: account"), contains(0.0)))
                .andExpect(jsonPath(item("takings", "Total collected"), contains(380.0)))
                .andExpect(jsonPath(item("cashUp", "Opening float"), contains(200.0)))
                .andExpect(jsonPath(item("cashUp", "Cash sales"), contains(290.0)))
                .andExpect(jsonPath(item("cashUp", "Cash refunds processed in this shift"), contains(50.0)))
                .andExpect(jsonPath(item("cashUp", "Expected cash"), contains(440.0)))
                .andExpect(jsonPath(item("cashUp", "Counted cash"), contains(440.0)))
                .andExpect(jsonPath(item("cashUp", "Variance"), contains(0.0)))
                .andExpect(jsonPath(rows("processed", "type"), contains("VOID")))
                .andExpect(jsonPath(rows("processed", "orderNumber"), contains(4)))
                .andExpect(jsonPath(rows("processed", "amount"), contains(50.0)))
                .andExpect(jsonPath(rows("processed", "paidOutAs"), contains("Cash")))
                .andExpect(jsonPath(item("rungByOthers", "Orders"), contains(0)))
                .andExpect(jsonPath(item("complimentary", "Complimentary meals"), contains(0)))
                .andExpect(jsonPath(item("signoff", "Status"), contains("Not yet signed off")))
                .andExpect(jsonPath("$.sections[?(@.key == 'signatures')].labels[*]", contains("Cashier", "Manager")))
                .andExpect(jsonPath("$.checks[*].key", contains("agrees-with-cash-up", "not-signed-off")))
                .andExpect(jsonPath("$.checks[*].status", contains("PASS", "WARN")))
                .andExpect(jsonPath("$.actions[0].key").value("SIGN_OFF"))
                .andExpect(jsonPath("$.actions[0].path").value("/admin/shifts/" + fixture.s1 + "/signoff"))
                .andExpect(jsonPath("$.actions[0].fields[0].name").value("note"))
                .andExpect(jsonPath("$.actions[0].fields[0].required").value(false))
                .andExpect(jsonPath("$.actions[*].key", not(hasItem("REVOKE_SIGN_OFF"))));
    }

    @Test
    void cashierReading_s2_crossShiftRefundAndComplimentary() throws Exception {
        run("cashier-reading", "shiftId=" + fixture.s2).andExpect(status().isOk())
                .andExpect(jsonPath(item("takings", "Orders (sales)"), contains(2)))
                .andExpect(jsonPath(item("takings", "Voided/refunded orders"), contains(0)))
                .andExpect(jsonPath(item("takings", "Gross sales"), contains(190.0)))
                .andExpect(jsonPath(item("takings", "Discounts at sale"), contains(10.0)))
                .andExpect(jsonPath(item("takings", "Net sales"), contains(180.0)))
                .andExpect(jsonPath(item("takings", "Collected: cash"), contains(60.0)))
                .andExpect(jsonPath(item("takings", "Collected: account"), contains(120.0)))
                .andExpect(jsonPath(item("takings", "Total collected"), contains(180.0)))
                .andExpect(jsonPath(item("cashUp", "Cash refunds processed in this shift"), contains(90.0)))
                .andExpect(jsonPath(item("cashUp", "Expected cash"), contains(270.0)))
                .andExpect(jsonPath(item("cashUp", "Counted cash"), contains(260.0)))
                .andExpect(jsonPath(item("cashUp", "Variance"), contains(-10.0)))
                .andExpect(jsonPath(item("cashUp", "Variance reason"), contains("Cash counting error")))
                .andExpect(jsonPath(item("cashUp", "Authorised by"), contains("Canteen Admin")))
                .andExpect(jsonPath(rows("processed", "type"), contains("REFUND")))
                .andExpect(jsonPath(rows("processed", "orderNumber"), contains(5)))
                .andExpect(jsonPath(rows("processed", "soldInEarlierShift"), contains("Sold in earlier shift")))
                .andExpect(jsonPath(item("complimentary", "Complimentary meals"), contains(1)))
                .andExpect(jsonPath(item("complimentary", "Value at list price"), contains(45.0)))
                .andExpect(jsonPath("$.checks[0].status").value("PASS"));
    }

    @Test
    void theftSuspectedVariance_isLabelledShortage() throws Exception {
        // The code stays THEFT_SUSPECTED in the database and API; only the display label changes.
        jdbc.update("UPDATE shifts SET variance_reason_code = 'THEFT_SUSPECTED' WHERE id = ?", fixture.s2);

        run("cashier-reading", "shiftId=" + fixture.s2).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.definitionVersion").value(2))
                .andExpect(jsonPath(item("cashUp", "Variance reason"), contains("Shortage")));
        run("cash-up-variance", "from=" + MON + "&to=" + TUE).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.shiftId == " + fixture.s2 + ")].reason", contains("Shortage")));
    }

    @Test
    void tillReading_ownerOrAdmin_interimForAnOpenShift_andSalesRungBySomeoneElse() throws Exception {
        // A cashier rings a sale on the admin's open shift S3.
        var orderId = fixture.order(fixture.s3, AuthTestHelper.cashierAId(mockMvc), TUESDAY, 4, 12, 50, "40.00", "40.00", "COLLECTED");
        fixture.payment(orderId, "CASH", "40.00");

        as(adminToken, get("/shifts/" + fixture.s3 + "/reading")).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.interim").value(true))
                .andExpect(jsonPath(item("header", "Status"), contains("Open (interim)")))
                .andExpect(jsonPath(item("cashUp", "Counted cash"), contains(nullValue())))
                .andExpect(jsonPath(item("rungByOthers", "Orders"), contains(1)))
                .andExpect(jsonPath(item("rungByOthers", "Value collected"), contains(40.0)))
                .andExpect(jsonPath(item("rungByOthers", "Rung by"), contains("Canteen Cashier")))
                .andExpect(jsonPath("$.checks[0].key").value("agrees-with-cash-up"))
                .andExpect(jsonPath("$.checks[0].status").value("PASS"));

        var cashier = AuthTestHelper.loginAsCashier(mockMvc);
        as(cashier, get("/shifts/" + fixture.s1 + "/reading")).andExpect(status().isOk())
                .andExpect(jsonPath("$.actions").doesNotExist());
        as(cashier, get("/shifts/" + fixture.s2 + "/reading")).andExpect(status().isForbidden());
        as(AuthTestHelper.loginAsStockAdmin(mockMvc), get("/shifts/" + fixture.s1 + "/reading")).andExpect(status().isForbidden());
        as(adminToken, get("/shifts/999999/reading")).andExpect(status().isNotFound());
    }

    // --- Sign-off and Close Day --------------------------------------------------------------

    @Test
    void signoff_rules_andAppendOnlyLog() throws Exception {
        signOff(fixture.s3).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_NOT_CLOSED"));
        signOff(fixture.s1).andExpect(status().isOk())
                .andExpect(jsonPath("$.signedOff").value(true))
                .andExpect(jsonPath("$.by").value("Canteen Admin"));
        signOff(fixture.s1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_SIGNED_OFF"));
        as(adminToken, post("/admin/shifts/" + fixture.s1 + "/signoff/revoke")).andExpect(status().isBadRequest());
        as(adminToken, post("/admin/shifts/" + fixture.s1 + "/signoff/revoke").contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Recount\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.signedOff").value(false));
        as(adminToken, post("/admin/shifts/" + fixture.s1 + "/signoff/revoke").contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Again\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_SIGNED_OFF"));
        signOff(fixture.s1).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT action FROM shift_signoffs WHERE shift_id = ? ORDER BY id", String.class, fixture.s1))
                .containsExactly("SIGNED_OFF", "REVOKED", "SIGNED_OFF");

        var cashier = AuthTestHelper.loginAsCashier(mockMvc);
        as(cashier, post("/admin/shifts/" + fixture.s2 + "/signoff")).andExpect(status().isForbidden());
    }

    @Test
    void closeDay_scenarios() throws Exception {
        // 1. Both Monday shifts unsigned: blocked, every blocker at once.
        closeDay(MON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSE_DAY_BLOCKED"))
                .andExpect(jsonPath("$.blockers[*].code", contains("SHIFT_NOT_SIGNED_OFF", "SHIFT_NOT_SIGNED_OFF")))
                .andExpect(jsonPath("$.blockers[*].shiftId", contains((int) fixture.s1, (int) fixture.s2)));
        // 2. S1 only: still blocked by S2.
        signOff(fixture.s1).andExpect(status().isOk());
        closeDay(MON).andExpect(status().isConflict()).andExpect(jsonPath("$.blockers[*].shiftId", contains((int) fixture.s2)));
        // 3. Both signed: Close Day is offered and the warning is gone. A signed-off reading
        // offers Revoke, which needs a reason.
        signOff(fixture.s2).andExpect(status().isOk());
        run("cashier-reading", "shiftId=" + fixture.s1)
                .andExpect(jsonPath("$.actions[*].key", contains("REVOKE_SIGN_OFF")))
                .andExpect(jsonPath("$.actions[0].path").value("/admin/shifts/" + fixture.s1 + "/signoff/revoke"))
                .andExpect(jsonPath("$.actions[0].fields[0].name").value("note"))
                .andExpect(jsonPath("$.actions[0].fields[0].required").value(true));
        run("x-reading", "tradingDay=" + MON)
                .andExpect(jsonPath("$.actions[0].enabled").value(true))
                .andExpect(jsonPath("$.actions[0].disabledReasons").isEmpty())
                .andExpect(jsonPath("$.checks[*].key", not(hasItem("unsigned-shifts"))));
        // 4. Z-0001.
        closeDay(MON).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.key").value("z-reading"))
                .andExpect(jsonPath("$.meta.statementTitle").value("Z Reading"))
                .andExpect(jsonPath("$.meta.reference").value("Z-0001"))
                .andExpect(jsonPath("$.actions").doesNotExist())
                .andExpect(jsonPath(item("header", "Z number"), contains("Z-0001")))
                .andExpect(jsonPath(item("header", "Closed by"), contains("Canteen Admin")))
                .andExpect(jsonPath(item("sales", "Net sales"), contains(420.0)))
                .andExpect(jsonPath(item("tender", "Total collected"), contains(560.0)))
                .andExpect(jsonPath(rows("signoffs", "shiftId"), contains((int) fixture.s1, (int) fixture.s2)))
                .andExpect(jsonPath(rows("signoffs", "signedOffBy"), contains("Canteen Admin", "Canteen Admin")))
                .andExpect(jsonPath("$.checks[*].status", contains("PASS", "PASS")));
        var stored = jdbc.queryForMap("SELECT z_number, trading_day, gross_sales, net_sales, definition_version FROM z_readings");
        assertThat(((Number) stored.get("z_number")).intValue()).isEqualTo(1);
        assertThat(stored.get("trading_day").toString()).isEqualTo(MON);
        assertThat(((java.math.BigDecimal) stored.get("net_sales"))).isEqualByComparingTo("420.00");
        run("x-reading", "tradingDay=" + MON).andExpect(jsonPath(item("header", "Day status"), contains("Closed, Z-0001")));

        // 5. Closed already; a sign-off can't be revoked any more (nor is it offered).
        run("cashier-reading", "shiftId=" + fixture.s1).andExpect(jsonPath("$.actions").doesNotExist());
        closeDay(MON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers[0].code").value("DAY_ALREADY_CLOSED"))
                .andExpect(jsonPath("$.blockers[0].zNumber").value(1));
        as(adminToken, post("/admin/shifts/" + fixture.s1 + "/signoff/revoke").contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Too late\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DAY_ALREADY_CLOSED"));

        // 6. Tuesday: S3 is open, so it can't be signed off either.
        closeDay(TUE).andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers[*].code", contains("SHIFT_OPEN", "SHIFT_NOT_SIGNED_OFF")))
                .andExpect(jsonPath("$.blockers[*].shiftId", contains((int) fixture.s3, (int) fixture.s3)));

        // 7. Tampering after the close: the Z is unchanged; the X shows the problem.
        jdbc.update("UPDATE orders SET total = 95.00 WHERE id = ?", fixture.o1);
        run("z-reading", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.reference").value("Z-0001"))
                .andExpect(jsonPath("$.meta.params.zNumber").value(1))
                .andExpect(jsonPath(item("sales", "Net sales"), contains(420.0)))
                .andExpect(jsonPath("$.checks[*].status", contains("PASS", "PASS")));
        run("x-reading", "tradingDay=" + MON)
                .andExpect(jsonPath("$.checks[?(@.key == 'x-agrees-with-reports')].status", contains("FAIL")));

        // 8. No shift can open on a closed day.
        clock.setInstant(MONDAY.atTime(16, 0).atZone(ClockConfig.BUSINESS_ZONE).toInstant());
        as(AuthTestHelper.loginAsCashier(mockMvc), post("/shifts/open").contentType(MediaType.APPLICATION_JSON)
                .content("{\"openingFloat\":100.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DAY_CLOSED"))
                .andExpect(jsonPath("$.zNumber").value(1));
    }

    @Test
    void firstTradingDay_isShownToTheFrontend() throws Exception {
        as(adminToken, get("/admin/settings/close-day")).andExpect(status().isOk())
                .andExpect(jsonPath("$.firstTradingDay").value(MON));
        run("x-reading", "tradingDay=" + TUE).andExpect(jsonPath(item("header", "Z numbering starts"), contains(MON)));
        as(AuthTestHelper.loginAsCashier(mockMvc), get("/admin/settings/close-day")).andExpect(status().isForbidden());
    }

    @Test
    void shiftsBeforeTheFirstTradingDay_areOutsideSignoff() throws Exception {
        // The fixture's Sat 26 Sep shift is before the first trading day (5 Oct here).
        var preRange = fixture.sPreRange;
        run("cashier-reading", "shiftId=" + preRange).andExpect(status().isOk())
                .andExpect(jsonPath("$.actions").doesNotExist())
                .andExpect(jsonPath("$.checks[*].key", not(hasItem("not-signed-off"))))
                .andExpect(jsonPath(item("signoff", "Status"), contains("Not required (Z numbering starts 5 Oct 2026)")));
        signOff(preRange).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BEFORE_FIRST_TRADING_DAY"))
                .andExpect(jsonPath("$.error").value("Shifts before the first trading day aren't signed off (Z numbering starts 5 Oct 2026)."));
        run("cash-up-variance", "from=2026-09-26&to=" + TUE + "&signoff=UNSIGNED")
                .andExpect(jsonPath("$.rows[*].shiftId", contains((int) fixture.s1, (int) fixture.s2)));
        run("cash-up-variance", "from=2026-09-26&to=2026-09-26")
                .andExpect(jsonPath("$.rows[0]._actions[*].key", contains("OPEN_READING")));
        run("x-reading", "tradingDay=2026-09-26")
                .andExpect(jsonPath("$.checks[*].key", not(hasItem("unsigned-shifts"))))
                .andExpect(jsonPath(rows("shifts", "signedOffBy"), contains("Not required")))
                .andExpect(jsonPath("$.actions[0].enabled").value(false))
                .andExpect(jsonPath("$.actions[0].disabledReasons[0]").value("Days before 2026-10-05 are not closed with a Z reading."));
    }

    @Test
    void closeDay_futureDay_andNoShifts() throws Exception {
        closeDay("2026-10-08").andExpect(status().isConflict()).andExpect(jsonPath("$.blockers[0].code").value("DAY_IN_FUTURE"));
        closeDay("2026-09-30").andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers[0].code").value("BEFORE_FIRST_TRADING_DAY"));
        clock.setInstant(TUESDAY.plusDays(5).atTime(9, 0).atZone(ClockConfig.BUSINESS_ZONE).toInstant());
        closeDay("2026-10-09").andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers[*].code", hasItem("NO_SHIFTS_FOR_DAY")));
    }

    @Test
    void closeDay_twoAtOnce_createsExactlyOneZ() throws Exception {
        signOff(fixture.s1).andExpect(status().isOk());
        signOff(fixture.s2).andExpect(status().isOk());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var calls = new ArrayList<Callable<Integer>>();
            for (var i = 0; i < 2; i++) {
                calls.add(() -> closeDay(MON).andReturn().getResponse().getStatus());
            }
            var statuses = new ArrayList<Integer>();
            for (var future : executor.invokeAll(calls)) {
                statuses.add(future.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM z_readings", Integer.class)).isEqualTo(1);
    }

    @Test
    void zReading_cantBeUpdatedOrDeleted() throws Exception {
        signOff(fixture.s1);
        signOff(fixture.s2);
        closeDay(MON).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> jdbc.update("UPDATE z_readings SET net_sales = 1"));
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.update("DELETE FROM z_readings"));
        assertThat(jdbc.queryForObject("SELECT net_sales FROM z_readings", java.math.BigDecimal.class)).isEqualByComparingTo("420.00");
    }

    @Test
    void zReading_withNoClosedDay_isAnEmptyStatement() throws Exception {
        run("z-reading", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.params.zNumber").value(nullValue()))
                .andExpect(jsonPath(item("empty", "Status"), contains("No day has been closed yet.")))
                .andExpect(jsonPath(item("empty", "Z numbering starts"), contains(MON)));
    }

    // --- Cash-up & Variance changes -----------------------------------------------------------

    @Test
    void cashUp_signoffColumns_unsignedFilter_andRowActions() throws Exception {
        run("cash-up-variance", "from=" + MON + "&to=" + TUE + "&signoff=UNSIGNED")
                .andExpect(jsonPath("$.rows[*].shiftId", contains((int) fixture.s1, (int) fixture.s2)))
                .andExpect(jsonPath("$.rows[0]._actions[*].key", contains("SIGN_OFF", "OPEN_READING")))
                .andExpect(jsonPath("$.rows[0]._actions[0].onSuccess").value("RELOAD"))
                .andExpect(jsonPath("$.rows[0]._actions[1].report").value("cashier-reading"))
                .andExpect(jsonPath("$.rows[0]._actions[1].params.shiftId").value((int) fixture.s1));
        signOff(fixture.s1);
        signOff(fixture.s2);
        run("cash-up-variance", "from=" + MON + "&to=" + TUE + "&signoff=UNSIGNED").andExpect(jsonPath("$.rows").isEmpty());
        run("cash-up-variance", "from=" + MON + "&to=" + TUE)
                .andExpect(jsonPath("$.meta.definitionVersion").value(4))
                .andExpect(jsonPath("$.rows[*].signedOffBy", contains("Canteen Admin", "Canteen Admin", null)))
                .andExpect(jsonPath("$.rows[0].signedOffAt").isString())
                .andExpect(jsonPath("$.rows[0]._actions[*].key", contains("OPEN_READING")))
                // S3 is open: no sign-off action.
                .andExpect(jsonPath("$.rows[2]._actions[*].key", contains("OPEN_READING")));
    }

    // --- Tender Summary check list and reprint -----------------------------------------------

    @Test
    void tenderChecks_listsSalesOrdersWithPayments_totalsAgree() throws Exception {
        run("tender-summary", "from=" + MON + "&to=" + TUE + "&groupBy=CHECK").andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.definitionVersion").value(3))
                .andExpect(jsonPath("$.rows[*].date", contains(MON, MON, MON, MON, MON, MON, MON, TUE, TUE)))
                .andExpect(jsonPath("$.rows[*].checkNumber", contains(1, 2, 3, 4, 5, 6, 7, 1, 2)))
                .andExpect(jsonPath("$.rows[0].time").value("2026-10-05T05:20:00Z"))
                .andExpect(jsonPath("$.rows[*].rungBy", contains("Canteen Cashier", "Canteen Cashier", "Canteen Cashier",
                        "Canteen Cashier", "Canteen Cashier", "Canteen Cashier B", "Canteen Cashier B", "Canteen Admin", "Canteen Admin")))
                .andExpect(jsonPath("$.rows[*].cash", contains(100.0, 0.0, 50.0, 50.0, 90.0, 0.0, 60.0, 40.0, 0.0)))
                .andExpect(jsonPath("$.rows[*].card", contains(0.0, 60.0, 30.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))
                .andExpect(jsonPath("$.rows[*].account", contains(0.0, 0.0, 0.0, 0.0, 0.0, 120.0, 0.0, 0.0, 50.0)))
                .andExpect(jsonPath("$.rows[*].total", contains(100.0, 60.0, 80.0, 50.0, 90.0, 120.0, 60.0, 40.0, 50.0)))
                .andExpect(jsonPath("$.rows[3].status").value("VOIDED"))
                .andExpect(jsonPath("$.rows[4].status").value("REFUNDED"))
                .andExpect(jsonPath("$.rows[5]._actions[0].key").value("REPRINT"))
                .andExpect(jsonPath("$.rows[5]._actions[0].path").value("/admin/orders/" + fixture.o6 + "/reprint-receipt"))
                .andExpect(jsonPath("$.rows[5]._actions[0].onSuccess").value("PRINT_RECEIPT"))
                .andExpect(jsonPath("$.totals.cash").value(390.00))
                .andExpect(jsonPath("$.totals.card").value(90.00))
                .andExpect(jsonPath("$.totals.account").value(170.00))
                .andExpect(jsonPath("$.totals.total").value(650.00))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(50))
                .andExpect(jsonPath("$.page.totalRows").value(9))
                .andExpect(jsonPath("$.page.paged").value(true))
                .andExpect(jsonPath("$.checks[0].key").value("rows-agree-with-daily-totals"))
                .andExpect(jsonPath("$.checks[0].status").value("PASS"));

        // Paging: totals still cover every row.
        run("tender-summary", "from=" + MON + "&to=" + TUE + "&groupBy=CHECK&page=1&size=4")
                .andExpect(jsonPath("$.rows[*].checkNumber", contains(5, 6, 7, 1)))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalRows").value(9))
                .andExpect(jsonPath("$.totals.total").value(650.00));
        run("tender-summary", "from=" + MON + "&to=" + TUE + "&groupBy=CHECK&checkNumber=2")
                .andExpect(jsonPath("$.rows[*].date", contains(MON, TUE)))
                .andExpect(jsonPath("$.page.totalRows").value(2));
        run("tender-summary", "from=2026-09-01&to=2026-10-06&groupBy=CHECK").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.to").value("The check list covers at most 31 days."));
        run("tender-summary", "from=" + MON + "&to=" + TUE + "&groupBy=CHECK&size=500").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.size").exists());
        // The DAY grouping is unchanged and isn't paged; Check # only applies to CHECK, so it's
        // ignored there, even when it isn't a number.
        run("tender-summary", "from=" + MON + "&to=" + TUE + "&checkNumber=abc").andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(nullValue()))
                .andExpect(jsonPath("$.meta.params.checkNumber").value(nullValue()))
                .andExpect(jsonPath("$.totals.totalCollected").value(650.00));
        as(adminToken, get("/admin/reports"))
                .andExpect(jsonPath("$[?(@.key == 'tender-summary')].params[?(@.name == 'checkNumber')].appliesWhen.param",
                        contains("groupBy")))
                .andExpect(jsonPath("$[?(@.key == 'tender-summary')].params[?(@.name == 'checkNumber')].appliesWhen.values[*]",
                        contains("CHECK")));
    }

    @Test
    void reprint_logsIncreasingCopies_andLeavesTheOrderAlone() throws Exception {
        as(adminToken, post("/admin/orders/" + fixture.o6 + "/reprint-receipt")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) fixture.o6))
                .andExpect(jsonPath("$.orderNumber").value(6))
                .andExpect(jsonPath("$.status").value("COLLECTED"))
                .andExpect(jsonPath("$.reprint.copyNumber").value(1))
                .andExpect(jsonPath("$.reprint.reprintedBy.name").value("Canteen Admin"));
        as(adminToken, post("/admin/orders/" + fixture.o6 + "/reprint-receipt"))
                .andExpect(jsonPath("$.reprint.copyNumber").value(2));
        assertThat(jdbc.queryForList("SELECT copy_number FROM receipt_reprints WHERE order_id = ? ORDER BY id", Integer.class,
                fixture.o6)).containsExactly(1, 2);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, fixture.o6)).isEqualTo("COLLECTED");

        as(AuthTestHelper.loginAsCashier(mockMvc), post("/admin/orders/" + fixture.o6 + "/reprint-receipt"))
                .andExpect(status().isForbidden());
        as(adminToken, post("/admin/orders/999999/reprint-receipt")).andExpect(status().isNotFound());
    }

    // --- Access and export --------------------------------------------------------------------

    @Test
    void otherRoles_get403() throws Exception {
        for (var token : List.of(AuthTestHelper.loginAsCashier(mockMvc), AuthTestHelper.loginAsStockAdmin(mockMvc),
                AuthTestHelper.loginAsKitchen(mockMvc), AuthTestHelper.loginAsHeadChef(mockMvc))) {
            for (var key : List.of("x-reading", "cashier-reading?shiftId=" + fixture.s1, "z-reading")) {
                as(token, get("/admin/reports/" + key)).andExpect(status().isForbidden());
            }
            as(token, post("/admin/days/" + MON + "/close")).andExpect(status().isForbidden());
        }
    }

    @Test
    void statementExport_isXlsxOnly_withEverySectionAndAChecksSheet() throws Exception {
        as(adminToken, get("/admin/reports/x-reading/export?format=csv&tradingDay=" + MON)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.format").value("Statements export as xlsx only."));
        var response = as(adminToken, get("/admin/reports/x-reading/export?format=xlsx&tradingDay=" + MON))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition")).contains("x-reading_2026-10-05.xlsx");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet = workbook.getSheet("Report");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("X Reading");
            var labels = new ArrayList<String>();
            for (var row : sheet) {
                if (row.getCell(0) != null && row.getCell(0).getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                    labels.add(row.getCell(0).getStringCellValue());
                }
            }
            assertThat(labels).contains("Sales", "Net sales", "Tender collected", "Cash", "Shifts", "Cashier readings vs X reading",
                    "Manager:");
            assertThat(workbook.getSheet("Checks").getRow(1).getCell(1).getStringCellValue()).isEqualTo("PASS");
        }
    }
}
