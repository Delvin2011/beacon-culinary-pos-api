package com.beaconculinary.api.locale;

import com.beaconculinary.api.dashboard.DashboardCache;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.ReportFixture;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0-2 (site locale): the X reading, Z reading, cashier readings, dashboard and report exports on
 * the R1–R3 fixture, compared with what the code produced before SiteLocale (see {@link Golden}).
 * Instants are written as UTC literals so this test doesn't lean on the zone it is guarding.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = "reports.close-day.first-trading-day=2026-10-05")
class GoldenOutputIntegrationTests {
    /** Tue 6 Oct 2026 13:00 Johannesburg. */
    private static final Instant TUESDAY_13_00 = Instant.parse("2026-10-06T11:00:00Z");
    /** Mon 5 Oct 2026 15:30 Johannesburg. */
    private static final Instant MONDAY_15_30 = Instant.parse("2026-10-05T13:30:00Z");
    private static final String MON = "2026-10-05";
    private static final String RANGE = "from=2026-09-26&to=2026-10-06";

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
    private Golden golden;
    private List<Long> parkedLightMeals = List.of();
    private final Map<Long, String> parkedRequests = new HashMap<>();
    private List<Long> parkedCounts = List.of();

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(TUESDAY_13_00);
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        LocaleTestData.clearOfflineAndTillLeftovers(jdbc);
        ReportFixture.clear(jdbc);
        var thandiwe = AuthTestHelper.cashierAId(mockMvc);
        var sipho = AuthTestHelper.cashierBId(mockMvc);
        var admin = AuthTestHelper.adminId(mockMvc);
        fixture = ReportFixture.seed(jdbc, thandiwe, sipho, admin);
        golden = Golden.create()
                .name(fixture.s1, "s1").name(fixture.s2, "s2").name(fixture.s3, "s3").name(fixture.sPreRange, "sPreRange")
                .name(fixture.o1, "o1").name(fixture.o2, "o2").name(fixture.o3, "o3").name(fixture.o4, "o4")
                .name(fixture.o5, "o5").name(fixture.o6, "o6").name(fixture.o7, "o7").name(fixture.o8, "o8")
                .name(fixture.o9, "o9").name(fixture.o10, "o10").name(fixture.o11, "o11").name(fixture.oPreRange, "oPreRange")
                .name(fixture.a1, "a1").name(fixture.a2, "a2").name(fixture.a3, "a3")
                .name(thandiwe, "thandiwe").name(sipho, "sipho").name(admin, "admin");
        fixture.catalog.forEach((name, id) -> golden.name(id, "catalog " + name));
        var settlements = jdbc.queryForList("SELECT id FROM account_payments WHERE account_id IN (?, ?, ?) ORDER BY id",
                Long.class, fixture.a1, fixture.a2, fixture.a3);
        for (int i = 0; i < settlements.size(); i++) {
            golden.name(settlements.get(i), "pay" + (i + 1));
        }

        // As in DashboardIntegrationTests: park what other test classes leave behind, so the
        // attention section only sees the fixture.
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

    private ResultActions get200(String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
    }

    private String body(String path) throws Exception {
        return get200(path).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void readings() throws Exception {
        golden.json("x-reading-mon.json", body("/admin/reports/x-reading?tradingDay=" + MON));
        golden.json("cashier-reading-s1.json", body("/admin/reports/cashier-reading?shiftId=" + fixture.s1));
        golden.json("cashier-reading-s2.json", body("/admin/reports/cashier-reading?shiftId=" + fixture.s2));
        golden.json("cashier-reading-s3-open.json", body("/admin/reports/cashier-reading?shiftId=" + fixture.s3));
        golden.json("till-shift-reading-s1.json", body("/shifts/" + fixture.s1 + "/reading"));

        for (var shift : List.of(fixture.s1, fixture.s2)) {
            mockMvc.perform(post("/admin/shifts/" + shift + "/signoff").header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        }
        var closed = mockMvc.perform(post("/admin/days/" + MON + "/close").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        golden.json("z-reading-close-mon.json", closed);
        golden.json("z-reading-latest.json", body("/admin/reports/z-reading"));
        golden.json("x-reading-mon-after-z.json", body("/admin/reports/x-reading?tradingDay=" + MON));
    }

    @Test
    void exports() throws Exception {
        golden.xlsx("x-reading-mon.xlsx.txt", get200("/admin/reports/x-reading/export?format=xlsx&tradingDay=" + MON)
                .andReturn().getResponse().getContentAsByteArray());
        golden.xlsx("cashier-reading-s1.xlsx.txt", get200("/admin/reports/cashier-reading/export?format=xlsx&shiftId=" + fixture.s1)
                .andReturn().getResponse().getContentAsByteArray());
        golden.text("sales-summary.csv", body("/admin/reports/sales-summary/export?format=csv&" + RANGE));
        golden.text("cash-up-variance.csv", body("/admin/reports/cash-up-variance/export?format=csv&" + RANGE));
        golden.text("account-statement-a1.csv",
                body("/admin/reports/account-statement/export?format=csv&" + RANGE + "&accountId=" + fixture.a1));
        golden.xlsx("account-statement-a1.xlsx.txt",
                get200("/admin/reports/account-statement/export?format=xlsx&" + RANGE + "&accountId=" + fixture.a1)
                        .andReturn().getResponse().getContentAsByteArray());
        golden.json("account-statement-a1.json", body("/admin/reports/account-statement?" + RANGE + "&accountId=" + fixture.a1));
        golden.json("cash-up-variance.json", body("/admin/reports/cash-up-variance?" + RANGE));
    }

    @Test
    void dashboard() throws Exception {
        clock.setInstant(MONDAY_15_30);
        golden.json("dashboard-layout.json", body("/admin/dashboard/layout"));
        for (var section : List.of("today", "attention", "now", "account-customers")) {
            golden.json("dashboard-" + section + ".json", body("/admin/dashboard/sections/" + section));
        }
    }
}
