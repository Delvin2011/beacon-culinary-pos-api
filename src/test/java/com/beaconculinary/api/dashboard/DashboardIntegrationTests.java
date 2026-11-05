package com.beaconculinary.api.dashboard;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.ReportFixture;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.beaconculinary.api.support.ReportFixture.MONDAY;
import static com.beaconculinary.api.support.ReportFixture.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Back-office dashboard acceptance (work order §6) on the R1 fixture with the discount,
 * complimentary and R2 additions, at Mon 2026-10-05 15:30 and Tue 2026-10-06 15:30. Z numbering
 * (the end-of-day process) starts on Mon 5 Oct, as in the R3 tests: the R2 fixture's Sat 26 Sep
 * shift sits before it, so it is never reported as unsigned or not closed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = "reports.close-day.first-trading-day=2026-10-05")
class DashboardIntegrationTests {
    private static final String MON = "2026-10-05";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private DashboardCache cache;

    private String adminToken;
    private ReportFixture fixture;
    private long thandiwe;
    private long admin;
    /** Left behind by other test classes; parked so they can't add attention items, then restored. */
    private List<Long> parkedLightMeals = List.of();
    private final Map<Long, String> parkedRequests = new HashMap<>();
    private List<Long> parkedCounts = List.of();

    @BeforeEach
    void setUp() throws Exception {
        at(MONDAY, 15, 30);
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        ReportFixture.clear(jdbc);
        thandiwe = AuthTestHelper.cashierAId(mockMvc);
        admin = AuthTestHelper.adminId(mockMvc);
        fixture = ReportFixture.seed(jdbc, thandiwe, AuthTestHelper.cashierBId(mockMvc), admin);

        parkedLightMeals = jdbc.queryForList("""
                SELECT mc.id FROM meal_catalog mc JOIN catalog_categories c ON c.id = mc.category_id
                WHERE mc.active = 1 AND c.group_code = 'LIGHT_MEALS'""", Long.class);
        parkedLightMeals.forEach(id -> jdbc.update("UPDATE meal_catalog SET active = 0 WHERE id = ?", id));
        jdbc.query("SELECT id, status FROM stock_requests WHERE status IN ('REQUESTED', 'PARTIALLY_ACTIONED')",
                rs -> {
                    parkedRequests.put(rs.getLong("id"), rs.getString("status"));
                });
        parkedRequests.keySet().forEach(id -> jdbc.update("UPDATE stock_requests SET status = 'CANCELLED' WHERE id = ?", id));
        parkedCounts = jdbc.queryForList("SELECT id FROM stock_takes WHERE status = 'SUBMITTED'", Long.class);
        parkedCounts.forEach(id -> jdbc.update("UPDATE stock_takes SET status = 'CANCELLED' WHERE id = ?", id));
        cache.clear();
    }

    @AfterEach
    void tearDown() {
        parkedLightMeals.forEach(id -> jdbc.update("UPDATE meal_catalog SET active = 1 WHERE id = ?", id));
        parkedRequests.forEach((id, status) -> jdbc.update("UPDATE stock_requests SET status = ? WHERE id = ?", status, id));
        parkedCounts.forEach(id -> jdbc.update("UPDATE stock_takes SET status = 'SUBMITTED' WHERE id = ?", id));
        clock.reset();
        cache.clear();
        ReportFixture.clear(jdbc);
    }

    private void at(LocalDate day, int hour, int minute) {
        clock.setInstant(day.atTime(hour, minute).atZone(ClockConfig.BUSINESS_ZONE).toInstant());
    }

    private ResultActions as(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    private ResultActions section(String key, String query) throws Exception {
        return as(adminToken, "/admin/dashboard/sections/" + key + (query == null ? "" : "?" + query));
    }

    private String body(ResultActions result) throws Exception {
        return result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String tile(String key) {
        return "$.data.tiles[?(@.key == '" + key + "')]";
    }

    // --- Layout and permissions ----------------------------------------------------------------

    @Test
    void layout_admin_everySectionInDisplayOrder_noSectionData() throws Exception {
        as(adminToken, "/admin/dashboard/layout").andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Africa/Johannesburg"))
                .andExpect(jsonPath("$.asOf").exists())
                .andExpect(jsonPath("$.sections[*].key", contains("attention", "now", "today", "sales-trend", "sales-mix",
                        "busy-hours", "heatmap", "top-sellers", "sell-through", "light-meal-stock", "payment-mix", "basket",
                        "waiting", "account-customers")))
                .andExpect(jsonPath("$.sections[0].title").value("Needs attention"))
                .andExpect(jsonPath("$.sections[0].usesRange").value(false))
                .andExpect(jsonPath("$.sections[0].refreshSeconds").value(30))
                .andExpect(jsonPath("$.sections[3].usesRange").value(true))
                .andExpect(jsonPath("$.sections[6].refreshSeconds").value(300))
                .andExpect(jsonPath("$.sections[0].data").doesNotExist());
    }

    @Test
    void headChef_seesKitchenSections_withoutMoney() throws Exception {
        var chef = AuthTestHelper.loginAsHeadChef(mockMvc);
        as(chef, "/admin/dashboard/layout").andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[*].key", contains("attention", "now", "busy-hours", "top-sellers",
                        "sell-through", "light-meal-stock", "waiting")));
        for (var key : List.of("attention", "now", "busy-hours", "top-sellers", "sell-through", "light-meal-stock", "waiting")) {
            as(chef, "/admin/dashboard/sections/" + key).andExpect(status().isOk());
        }
        for (var key : List.of("today", "sales-trend", "sales-mix", "heatmap", "payment-mix", "basket", "account-customers")) {
            as(chef, "/admin/dashboard/sections/" + key).andExpect(status().isForbidden());
        }
        as(chef, "/admin/dashboard/sections/now").andExpect(jsonPath("$.data.onTheTill").doesNotExist())
                .andExpect(jsonPath("$.data.kitchen.open").value(0));
        as(chef, "/admin/dashboard/sections/top-sellers?range=today")
                .andExpect(jsonPath("$.data.items[*].portions", contains(5, 4, 2, 2)))
                .andExpect(jsonPath("$.data.items[0].soldValue").doesNotExist())
                .andExpect(jsonPath("$.data.items[3].soldValue").doesNotExist());
        // Sorting by a value the caller can't see would leak it.
        as(chef, "/admin/dashboard/sections/top-sellers?range=today&sortBy=VALUE").andExpect(status().isForbidden());
    }

    @Test
    void layout_forbiddenWithoutDashboardView() throws Exception {
        for (var token : List.of(AuthTestHelper.loginAsCashier(mockMvc), AuthTestHelper.loginAsKitchen(mockMvc),
                AuthTestHelper.loginAsBarista(mockMvc), AuthTestHelper.loginAsStockAdmin(mockMvc),
                AuthTestHelper.loginAsStockClerk(mockMvc))) {
            as(token, "/admin/dashboard/layout").andExpect(status().isForbidden());
            as(token, "/admin/dashboard/sections/attention").andExpect(status().isForbidden());
        }
    }

    @Test
    void errors_unknownKey404_badRange400_badParam400() throws Exception {
        section("no-such-section", null).andExpect(status().isNotFound());
        section("sales-trend", "range=year").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.range").value("Range must be today, 7d or month."));
        section("today", "range=year").andExpect(status().isBadRequest());
        section("top-sellers", "sortBy=PRICE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.sortBy").exists());
        section("top-sellers", "type=DRINK").andExpect(status().isBadRequest());
    }

    // --- Monday 15:30: the expected values -----------------------------------------------------

    @Test
    void today_monday() throws Exception {
        section("today", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("today"))
                .andExpect(jsonPath("$.range").value("7d"))
                .andExpect(jsonPath("$.definitionVersion").value(1))
                .andExpect(jsonPath("$.asOf").exists())
                .andExpect(jsonPath("$.data.tiles[*].key", contains("netSales", "orders", "averageOrder", "voidsRefunds",
                        "complimentary", "discounts")))
                .andExpect(jsonPath(tile("netSales") + ".value", contains(420.0)))
                .andExpect(jsonPath(tile("netSales") + ".type", contains("MONEY")))
                .andExpect(jsonPath(tile("orders") + ".value", contains(7)))
                .andExpect(jsonPath(tile("averageOrder") + ".value", contains(84.0)))
                .andExpect(jsonPath(tile("voidsRefunds") + ".value", contains(140.0)))
                .andExpect(jsonPath(tile("voidsRefunds") + ".secondary", contains("24.6% of gross sales · 2 orders")))
                .andExpect(jsonPath(tile("voidsRefunds") + ".tone", contains("WATCH")))
                .andExpect(jsonPath(tile("complimentary") + ".value", contains(1)))
                .andExpect(jsonPath(tile("complimentary") + ".secondary", contains("R 45,00 at list price")))
                .andExpect(jsonPath(tile("discounts") + ".value", contains(10.0)))
                .andExpect(jsonPath(tile("discounts") + ".secondary", contains("1 order")))
                // No earlier Monday traded: every delta is null, never zero.
                .andExpect(jsonPath("$.data.tiles[0].delta").value(nullValue()))
                .andExpect(jsonPath("$.data.tiles[1].delta").value(nullValue()))
                .andExpect(jsonPath("$.data.tiles[2].delta").value(nullValue()))
                .andExpect(jsonPath("$.data.compareLabel").value("vs last Mon, same time"))
                .andExpect(jsonPath("$.reportLink.key").value("sales-summary"))
                .andExpect(jsonPath("$.reportLink.params.from").value(MON))
                .andExpect(jsonPath("$.reportLink.params.to").value(MON));
    }

    @Test
    void busyHours_monday() throws Exception {
        section("busy-hours", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hours[*].hour", contains(6, 7, 8, 9, 10, 11, 12, 13, 14)))
                .andExpect(jsonPath("$.data.hours[*].today", contains(0, 2, 1, 1, 1, 1, 1, 0, 0)))
                .andExpect(jsonPath("$.data.hours[0].typical").value(nullValue()))
                .andExpect(jsonPath("$.data.peak.hour").value(7))
                .andExpect(jsonPath("$.data.peak.orders").value(2))
                .andExpect(jsonPath("$.data.typicalBasis").value(nullValue()));
    }

    @Test
    void paymentMix_monday() throws Exception {
        section("payment-mix", "range=today").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basis").value("MONEY_COLLECTED"))
                .andExpect(jsonPath("$.data.total").value(560.0))
                .andExpect(jsonPath("$.data.methods[*].method", contains("CASH", "CARD", "ACCOUNT")))
                .andExpect(jsonPath("$.data.methods[*].amount", contains(350.0, 90.0, 120.0)))
                .andExpect(jsonPath("$.data.methods[*].percent", contains(62.5, 16.1, 21.4)))
                .andExpect(jsonPath("$.reportLink.key").value("tender-summary"));
    }

    @Test
    void topSellers_monday() throws Exception {
        section("top-sellers", "range=today").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sortBy").value("PORTIONS"))
                // Ties break by name A–Z: Chicken & Rice above Pap & Stew. Four items only.
                .andExpect(jsonPath("$.data.items[*].name", contains("Breakfast Plate", "Extra Egg", "Chicken & Rice", "Pap & Stew")))
                .andExpect(jsonPath("$.data.items[*].rank", contains(1, 2, 3, 4)))
                .andExpect(jsonPath("$.data.items[*].portions", contains(5, 4, 2, 2)))
                .andExpect(jsonPath("$.data.items[*].soldValue", contains(200.0, 40.0, 120.0, 70.0)))
                .andExpect(jsonPath("$.data.items[*].tag", contains("SET_MEAL", "EXTRA", "SET_MEAL", "SET_MEAL")))
                .andExpect(jsonPath("$.reportLink.key").value("meal-extras-sales"));
        section("top-sellers", "range=today&sortBy=VALUE").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].name", contains("Breakfast Plate", "Chicken & Rice", "Pap & Stew", "Extra Egg")));
        section("top-sellers", "range=today&type=EXTRA").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].name", contains("Extra Egg")));
    }

    @Test
    void salesMix_monday() throws Exception {
        section("sales-mix", "range=today").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basis").value("BEFORE_DISCOUNTS"))
                .andExpect(jsonPath("$.data.total").value(430.0))
                .andExpect(jsonPath("$.data.groups[*].key", contains("BREAKFAST", "LUNCH")))
                .andExpect(jsonPath("$.data.groups[*].label", contains("Breakfast", "Lunch")))
                .andExpect(jsonPath("$.data.groups[*].amount", contains(240.0, 190.0)))
                .andExpect(jsonPath("$.data.groups[*].percent", contains(55.8, 44.2)));
    }

    @Test
    void attentionAndNow_monday() throws Exception {
        section("attention", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].key").value("UNSIGNED_SHIFTS"))
                .andExpect(jsonPath("$.data.items[0].severity").value("AMBER"))
                .andExpect(jsonPath("$.data.items[0].count").value(2))
                .andExpect(jsonPath("$.data.items[0].title").value("2 closed shifts are not signed off"))
                .andExpect(jsonPath("$.data.items[0].action.path").value("/end-of-day"))
                .andExpect(jsonPath("$.data.moreCount").value(0));
        // Tuesday's S3 is in the database but hasn't opened yet at Monday 15:30.
        var now = body(section("now", null)
                .andExpect(jsonPath("$.data.kitchen.open").value(0))
                .andExpect(jsonPath("$.data.kitchen.longestWaitMinutes").value(nullValue()))
                .andExpect(jsonPath("$.data.bar.open").value(0))
                .andExpect(jsonPath("$.data.readyToCollect.count").value(0)));
        // null (no shift open), not absent: the admin may see the till.
        assertThat(now).contains("\"onTheTill\":null");
    }

    @Test
    void otherSections_monday() throws Exception {
        section("basket", "range=today").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.averageOrder").value(84.0))
                // Not voided: O1 2, O2 1, O3 2, O5 2, O6 2, O7 2 = 11 meals over 6 orders; extras on O1, O2, O5.
                .andExpect(jsonPath("$.data.itemsPerOrder").value(1.8))
                .andExpect(jsonPath("$.data.extrasAttachPercent").value(50.0))
                .andExpect(jsonPath("$.data.series[*].day", contains("2026-09-26", MON)))
                .andExpect(jsonPath("$.data.series[*].value", contains(200.0, 84.0)));
        section("account-customers", "range=today").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts[*].name", contains("Acme Logistics")))
                .andExpect(jsonPath("$.data.accounts[0].amount").value(120.0))
                .andExpect(jsonPath("$.data.total").value(120.0))
                .andExpect(jsonPath("$.data.orders").value(1));
        section("sell-through", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].name", contains("Breakfast Plate", "Chicken & Rice", "Pap & Stew")))
                .andExpect(jsonPath("$.data.rows[*].planned", contains(20, 20, 20)))
                .andExpect(jsonPath("$.data.rows[*].sold", contains(5, 2, 2)))
                .andExpect(jsonPath("$.data.rows[*].sellThroughPercent", contains(25.0, 10.0, 10.0)))
                .andExpect(jsonPath("$.data.rows[*].slow", contains(false, true, true)))
                .andExpect(jsonPath("$.data.rows[0].soldOutAt").value(nullValue()));
        section("sales-trend", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[*].day", contains("2026-09-26", MON)))
                .andExpect(jsonPath("$.data.days[*].netSales", contains(200.0, 420.0)))
                .andExpect(jsonPath("$.data.days[*].isToday", contains(false, true)))
                .andExpect(jsonPath("$.data.days[1].compare").value(nullValue()));
        section("heatmap", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].weekday", contains("SAT")))
                .andExpect(jsonPath("$.data.max").value(1.0));
        section("waiting", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kitchenMedianMinutes").value(nullValue()))
                .andExpect(jsonPath("$.data.longest").value(nullValue()));
        section("light-meal-stock", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meals", empty()));
    }

    // --- Tuesday 15:30 -------------------------------------------------------------------------

    @Test
    void tuesday_dayNotClosed_andTwoUnsignedShifts_noOverdueShift() throws Exception {
        at(TUESDAY, 15, 30);
        section("attention", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].key", contains("DAY_NOT_CLOSED", "UNSIGNED_SHIFTS")))
                .andExpect(jsonPath("$.data.items[0].title").value("Mon 5 Oct is not closed"))
                .andExpect(jsonPath("$.data.items[1].count").value(2));
        // Tuesday compares with Tuesday 29 Sep, which had no shifts.
        section("today", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.compareDay").value("2026-09-29"))
                .andExpect(jsonPath("$.data.tiles[0].delta").value(nullValue()))
                .andExpect(jsonPath("$.data.tiles[1].delta").value(nullValue()))
                .andExpect(jsonPath("$.data.tiles[2].delta").value(nullValue()));
        // S3 has been open since 08:00 today.
        section("now", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onTheTill.since").value("08:00"))
                .andExpect(jsonPath("$.data.onTheTill.shiftId").value((int) fixture.s3))
                // Opening float 100 + cash on O8 (R40).
                .andExpect(jsonPath("$.data.onTheTill.expectedCash").value(140.0))
                .andExpect(jsonPath("$.data.readyToCollect.count").value(1));
    }

    // --- Same weekday, same time ---------------------------------------------------------------

    @Test
    void comparisons_sameWeekdaySameTime() throws Exception {
        // Mon 28 Sep traded: R100 at 09:00 and R50 at 16:00. At 15:30 only the 09:00 order counts.
        var lastMonday = MONDAY.minusDays(7);
        var shift = fixture.closedShift(thandiwe, "100.00", ReportFixture.utc(lastMonday, 8, 0),
                ReportFixture.utc(lastMonday, 17, 0), "250.00", "250.00", "0.00", null, null, thandiwe, "150.00", "0.00");
        fixture.payment(fixture.order(shift, thandiwe, lastMonday, 1, 9, 0, "100.00", "100.00", "COLLECTED"), "CASH", "100.00");
        fixture.payment(fixture.order(shift, thandiwe, lastMonday, 2, 16, 0, "50.00", "50.00", "COLLECTED"), "CASH", "50.00");

        section("today", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.compareLabel").value("vs last Mon, same time"))
                // (420 − 100) ÷ 100 = +320%; 7 orders vs 1 = +600%; R84 vs R100 = −16%.
                .andExpect(jsonPath(tile("netSales") + ".delta", contains(320)))
                .andExpect(jsonPath(tile("netSales") + ".tone", contains("POSITIVE")))
                .andExpect(jsonPath(tile("orders") + ".delta", contains(600)))
                .andExpect(jsonPath(tile("averageOrder") + ".delta", contains(-16)))
                .andExpect(jsonPath(tile("averageOrder") + ".tone", contains("WATCH")));
        section("sales-trend", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[*].day", contains("2026-09-26", "2026-09-28", MON)))
                // Last Monday itself is a completed day, compared in full (and with nothing).
                .andExpect(jsonPath("$.data.days[*].netSales", contains(200.0, 150.0, 420.0)))
                .andExpect(jsonPath("$.data.days[2].compare").value(100.0))
                .andExpect(jsonPath("$.data.days[1].compare").value(nullValue()));
        section("busy-hours", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.typicalBasis").value("Last Monday"))
                .andExpect(jsonPath("$.data.hours[3].hour").value(9))
                .andExpect(jsonPath("$.data.hours[3].typical").value(1.0))
                .andExpect(jsonPath("$.data.hours[1].typical").value(0.0));
    }

    // --- Every figure equals its report --------------------------------------------------------

    @Test
    void figuresEqualTheirReports() throws Exception {
        var today = body(section("today", null));
        var summary = body(as(adminToken, "/admin/reports/sales-summary?from=" + MON + "&to=" + MON));
        assertThat(num(today, tile("netSales") + ".value")).isEqualTo(num(summary, "$.totals.netSales"));
        assertThat(num(today, tile("orders") + ".value")).isEqualTo(num(summary, "$.totals.orders"));
        assertThat(num(today, tile("averageOrder") + ".value")).isEqualTo(num(summary, "$.totals.averageOrderValue"));
        assertThat(num(today, tile("discounts") + ".value")).isEqualTo(num(summary, "$.totals.discountsAtSale"));
        assertThat(num(today, tile("voidsRefunds") + ".value"))
                .isEqualTo(num(summary, "$.totals.voids") + num(summary, "$.totals.refunds"));
        assertThat(num(today, tile("complimentary") + ".value")).isEqualTo(num(summary, "$.totals.complimentaryOrders"));

        var trend = body(section("sales-trend", null));
        var from = JsonPath.<String>read(trend, "$.reportLink.params.from");
        var byDay = body(as(adminToken, "/admin/reports/sales-summary?groupBy=DAY&from=" + from + "&to=" + MON));
        List<Double> trendNet = JsonPath.read(trend, "$.data.days[*].netSales");
        List<Double> reportNet = JsonPath.read(byDay, "$.rows[*].netSales");
        assertThat(trendNet).isEqualTo(reportNet);

        var hours = body(section("busy-hours", null));
        var hourly = body(as(adminToken, "/admin/reports/sales-summary?groupBy=HOUR&from=" + MON + "&to=" + MON));
        List<String> periods = JsonPath.read(hourly, "$.rows[*].period");
        List<Integer> reportOrders = JsonPath.read(hourly, "$.rows[*].orders");
        for (int i = 0; i < periods.size(); i++) {
            var hour = Integer.parseInt(periods.get(i).substring(0, 2));
            List<Integer> dashboard = JsonPath.read(hours, "$.data.hours[?(@.hour == " + hour + ")].today");
            assertThat(dashboard).containsExactly(reportOrders.get(i));
        }

        var mix = body(section("payment-mix", "range=today"));
        var tender = body(as(adminToken, "/admin/reports/tender-summary?from=" + MON + "&to=" + MON));
        assertThat(num(mix, "$.data.methods[0].amount")).isEqualTo(num(tender, "$.totals.cash"));
        assertThat(num(mix, "$.data.methods[1].amount")).isEqualTo(num(tender, "$.totals.card"));
        assertThat(num(mix, "$.data.methods[2].amount")).isEqualTo(num(tender, "$.totals.account"));
        assertThat(num(mix, "$.data.total")).isEqualTo(num(tender, "$.totals.totalCollected"));

        var top = body(section("top-sellers", "range=today"));
        var items = body(as(adminToken, "/admin/reports/meal-extras-sales?from=" + MON + "&to=" + MON));
        List<String> names = JsonPath.read(top, "$.data.items[*].name");
        for (var name : names) {
            var quoted = name.replace("'", "\\'");
            List<Integer> netPortions = JsonPath.read(items, "$.rows[?(@.item == '" + quoted + "')].netPortions");
            List<Double> soldValue = JsonPath.read(items, "$.rows[?(@.item == '" + quoted + "')].soldValue");
            List<Integer> portions = JsonPath.read(top, "$.data.items[?(@.name == '" + quoted + "')].portions");
            List<Double> value = JsonPath.read(top, "$.data.items[?(@.name == '" + quoted + "')].soldValue");
            assertThat(portions.get(0)).isEqualTo(netPortions.stream().mapToInt(Integer::intValue).sum());
            assertThat(value.get(0)).isEqualTo(soldValue.stream().mapToDouble(Double::doubleValue).sum());
        }
    }

    private static double num(String json, String path) {
        Object value = JsonPath.read(json, path);
        if (value instanceof List<?> list) {
            value = list.get(0);
        }
        return ((Number) value).doubleValue();
    }

    // --- Caching -------------------------------------------------------------------------------

    @Test
    void sectionsAreCachedForTheirRefreshTime() throws Exception {
        var first = JsonPath.<String>read(body(section("today", null)), "$.asOf");
        // A new sales order in the database: still the cached figure within 60 s ...
        clock.setInstant(clock.instant().plusSeconds(30));
        fixture.payment(fixture.order(fixture.s2, thandiwe, MONDAY, 99, 13, 0, "10.00", "10.00", "COLLECTED"), "CASH", "10.00");
        var cached = body(section("today", null));
        assertThat(JsonPath.<String>read(cached, "$.asOf")).isEqualTo(first);
        assertThat(num(cached, tile("orders") + ".value")).isEqualTo(7);
        // ... and fresh after it.
        clock.setInstant(clock.instant().plusSeconds(31));
        var fresh = body(section("today", null));
        assertThat(JsonPath.<String>read(fresh, "$.asOf")).isNotEqualTo(first);
        assertThat(num(fresh, tile("orders") + ".value")).isEqualTo(8);
    }
}
