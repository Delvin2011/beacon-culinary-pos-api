package com.beaconculinary.api.offline;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.O3Tills.Cashier;
import com.beaconculinary.api.support.O3Tills.Till;
import com.beaconculinary.api.support.ReportFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.beaconculinary.api.support.O3Tills.body;
import static com.beaconculinary.api.support.O3Tills.on;
import static com.beaconculinary.api.support.ReportFixture.MONDAY;
import static com.beaconculinary.api.support.ReportFixture.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O3: unsynced offline sales block Close Day (OFFLINE_SALES_PENDING, with an audited override) and a
 * normal shift close (contract 10.3 and 10.4). Same context settings as EndOfDayIntegrationTests, on
 * the report fixture's Monday.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = "reports.close-day.first-trading-day=2026-10-05")
class O3OfflineBlockingIntegrationTests {
    private static final String PREFIX = "O3 Block ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private String adminPwd;
    private O3Tills tills;
    private ReportFixture fixture;
    private Till t1;
    private Till t2;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(TUESDAY.atTime(13, 0).atZone(ClockConfig.BUSINESS_ZONE).toInstant());
        adminPwd = AuthTestHelper.loginAsAdmin(mockMvc);
        ReportFixture.clear(jdbc);
        fixture = ReportFixture.seed(jdbc, AuthTestHelper.cashierAId(mockMvc), AuthTestHelper.cashierBId(mockMvc),
                AuthTestHelper.adminId(mockMvc));
        tills = new O3Tills(mockMvc, jdbc, PREFIX, adminPwd);
        t1 = tills.registerTill();
        t2 = tills.registerTill();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DISABLE TRIGGER trg_day_close_overrides_insert_only ON day_close_overrides; DELETE FROM day_close_overrides; "
                + "ENABLE TRIGGER trg_day_close_overrides_insert_only ON day_close_overrides;");
        tills.cleanUp();
        ReportFixture.clear(jdbc);
        clock.reset();
    }

    // ---- Close Day ----

    @Test
    void pendingSalesForTheDay_blockCloseDay_onePerTill() throws Exception {
        signOffMonday();
        report(t1, List.of(entry(fixture.s1, MONDAY, 2), entry(fixture.s2, MONDAY, 1)));
        report(t2, List.of(entry(fixture.s2, TUESDAY, 4)));

        var result = closeDay(adminPwd, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        var body = body(result);
        assertThat(body.get("code").asText()).isEqualTo("CLOSE_DAY_BLOCKED");
        var blockers = blockers(body);
        assertThat(blockers).hasSize(1);
        var blocker = blockers.get(0);
        assertThat(blocker.get("code").asText()).isEqualTo("OFFLINE_SALES_PENDING");
        assertThat(blocker.get("tillCode").asText()).isEqualTo(t1.code());
        assertThat(blocker.get("date").asText()).isEqualTo(MONDAY.toString());
        assertThat(blocker.get("message").asText())
                .startsWith(t1.code() + " (" + t1.name() + ") has 3 unsynced sales for this day (last report 13:00)");
        assertThat(blocker.has("shiftId")).isFalse();
    }

    @Test
    void aTillThatWentSilent_afterReportingPendingSales_stillBlocks() throws Exception {
        signOffMonday();
        report(t1, List.of(entry(fixture.s1, MONDAY, 1)));
        clock.setInstant(clock.instant().plusSeconds(11 * 60));

        var blocker = blockers(body(closeDay(adminPwd, null))).get(0);

        assertThat(blocker.get("code").asText()).isEqualTo("OFFLINE_SALES_PENDING");
        assertThat(blocker.get("message").asText()).contains("1 unsynced sale for this day").contains("nothing heard since");
    }

    @Test
    void onceTheTillReportsNothingPending_theDayCloses() throws Exception {
        signOffMonday();
        report(t1, List.of(entry(fixture.s1, MONDAY, 1)));
        report(t1, List.of());

        assertThat(closeDay(adminPwd, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(overrides()).isZero();
    }

    @Test
    void anOverride_closesTheDay_andIsRecorded() throws Exception {
        signOffMonday();
        report(t1, List.of(entry(fixture.s1, MONDAY, 3)));

        var result = closeDay(adminPwd, Map.of("overrideOfflinePending", true, "reason", "  Till 1 broken; sales captured Monday.  "));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(result).get("meta").get("reference").asText()).startsWith("Z-");
        var row = jdbc.queryForMap("SELECT * FROM [day_close_overrides]");
        assertThat(row.get("trading_day").toString()).isEqualTo(MONDAY.toString());
        assertThat(row.get("blocker_code")).isEqualTo("OFFLINE_SALES_PENDING");
        assertThat(row.get("reason")).isEqualTo("Till 1 broken; sales captured Monday.");
        assertThat(row.get("overridden_by")).isEqualTo(AuthTestHelper.adminId(mockMvc));
        assertThat(row.get("overridden_by_name")).isEqualTo("Canteen Admin");
        var detail = MAPPER.readTree((String) row.get("detail"));
        assertThat(detail.get("tillCode").asText()).isEqualTo(t1.code());
        assertThat(detail.get("pendingCount").asInt()).isEqualTo(3);
        assertThat(detail.get("deviceId").asText()).isEqualTo(t1.id());
        assertThat(detail.get("stale").asBoolean()).isFalse();
    }

    @Test
    void anOverride_needsARecentPasswordSignIn_andAReason() throws Exception {
        signOffMonday();
        report(t1, List.of(entry(fixture.s1, MONDAY, 3)));

        assertError(closeDay(adminPwd, Map.of("overrideOfflinePending", true, "reason", "   ")), 400, null);
        assertThat(body(closeDay(adminPwd, Map.of("overrideOfflinePending", true, "reason", "   "))).has("reason")).isTrue();
        assertThat(body(closeDay(adminPwd, Map.of("overrideOfflinePending", true))).has("reason")).isTrue();
        assertThat(body(closeDay(adminPwd, Map.of("overrideOfflinePending", true, "reason", "r".repeat(501)))).has("reason")).isTrue();

        clock.setInstant(clock.instant().plusSeconds(31 * 60));
        assertError(closeDay(adminPwd, Map.of("overrideOfflinePending", true, "reason", "broken")), 403, "REAUTH_REQUIRED");
        assertThat(overrides()).isZero();
    }

    @Test
    void anOverride_liftsOnlyTheOfflineBlocker() throws Exception {
        report(t1, List.of(entry(fixture.s1, MONDAY, 3)));

        var result = closeDay(adminPwd, Map.of("overrideOfflinePending", true, "reason", "broken till"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(blockers(body(result))).extracting(node -> node.get("code").asText())
                .containsOnly("SHIFT_NOT_SIGNED_OFF");
        assertThat(overrides()).isZero();
    }

    @Test
    void anOverrideWithNothingToOverride_closesNormally_andRecordsNothing() throws Exception {
        signOffMonday();

        var result = closeDay(adminPwd, Map.of("overrideOfflinePending", true, "reason", "just in case"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(overrides()).isZero();
    }

    // ---- shift close ----

    @Test
    void pendingSalesForTheShift_blockItsClose_butNotAForceClose() throws Exception {
        Cashier cashier = tills.createCashier();
        var token = tills.pinLogin(cashier, t1);
        var shiftId = tills.openShift(token, t1);
        report(t1, List.of(entry(shiftId, TUESDAY, 2)));

        var refused = closeShift(token, shiftId);
        assertError(refused, 409, "OFFLINE_SALES_PENDING");
        var details = body(refused).get("details");
        assertThat(details.get("pendingCount").asInt()).isEqualTo(2);
        assertThat(details.get("stale").asBoolean()).isFalse();
        assertThat(details.get("lastReportAt").asText()).endsWith("Z");

        clock.setInstant(clock.instant().plusSeconds(11 * 60));
        assertThat(body(closeShift(token, shiftId)).get("details").get("stale").asBoolean()).isTrue();

        var force = mockMvc.perform(post("/admin/shifts/" + shiftId + "/force-close").header("Authorization", "Bearer " + tills.adminLogin(null))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Till offline, sales to follow\"}")).andReturn();
        assertThat(force.getResponse().getStatus()).as(force.getResponse().getContentAsString()).isEqualTo(200);
    }

    @Test
    void anotherTillsReport_orNothingPending_doesntBlockAShiftClose() throws Exception {
        Cashier cashier = tills.createCashier();
        var token = tills.pinLogin(cashier, t1);
        var shiftId = tills.openShift(token, t1);
        report(t2, List.of(entry(shiftId, TUESDAY, 2)));
        report(t1, List.of());

        var closed = closeShift(token, shiftId);

        assertThat(closed.getResponse().getStatus()).as(closed.getResponse().getContentAsString()).isEqualTo(200);
    }

    // ---- helpers ----

    private void signOffMonday() throws Exception {
        for (var shiftId : new long[]{fixture.s1, fixture.s2}) {
            var result = mockMvc.perform(post("/admin/shifts/" + shiftId + "/signoff").header("Authorization", "Bearer " + adminPwd))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        }
    }

    private void report(Till till, List<Map<String, Object>> pending) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("pendingCount", pending.stream().mapToInt(entry -> (Integer) entry.get("count")).sum());
        body.put("tillClockUtc", clock.instant().toString());
        body.put("pending", pending);
        var result = mockMvc.perform(on(post("/pos/offline/status"), till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
    }

    private static Map<String, Object> entry(long shiftId, LocalDate day, int count) {
        return Map.of("shiftId", shiftId, "tradingDay", day.toString(), "count", count);
    }

    private MvcResult closeDay(String token, Map<String, Object> body) throws Exception {
        var request = post("/admin/days/" + MONDAY + "/close").header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult closeShift(String token, long shiftId) throws Exception {
        return mockMvc.perform(on(post("/shifts/" + shiftId + "/close"), t1).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":500.00}")).andReturn();
    }

    private static List<JsonNode> blockers(JsonNode body) {
        var list = new ArrayList<JsonNode>();
        body.get("blockers").forEach(list::add);
        return list;
    }

    private int overrides() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM [day_close_overrides]", Integer.class);
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        if (code != null) {
            assertThat(body(result).get("code").asText()).isEqualTo(code);
        }
    }
}
