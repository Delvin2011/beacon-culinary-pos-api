package com.beaconculinary.api.settings;

import com.beaconculinary.api.dashboard.DashboardCache;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.ReportFixture;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0-4a step 5: GET/PUT /admin/settings, POST /admin/settings/{key}/reset and GET
 * /admin/settings/history (contract section 3). Every test starts and ends with nothing saved and an
 * empty history (SiteSettingsFixture.clearSavedAndHistory).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class SiteSettingsAdminIntegrationTests {
    /** Reads decimals exactly, so 75.50 in a response stays 75.50. */
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SiteSettings siteSettings;
    @Autowired
    private DashboardCache dashboardCache;
    @Autowired
    private MutableClock clock;

    private String adminToken;
    private String cashierToken;
    private String adminName;

    @BeforeEach
    void setUp() throws Exception {
        clearSaved();
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        adminName = jdbc.queryForObject("SELECT name FROM users WHERE email = ?", String.class, AuthTestHelper.ADMIN_EMAIL);
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        clearSaved();
    }

    private void clearSaved() {
        SiteSettingsFixture.clearSavedAndHistory(jdbc);
        siteSettings.invalidate();
    }

    // ---- GET -----------------------------------------------------------------------------------

    @Test
    void get_beforeAnySave_everySettingIsAtItsDefault() throws Exception {
        var body = body(getAs("/admin/settings", adminToken).andExpect(status().isOk()));

        assertThat(body.get("offlineEntitled").asBoolean()).isFalse();
        var settings = body.get("settings");
        assertThat(settings).hasSize(16);
        for (var setting : settings) {
            assertThat(setting.get("isDefault").asBoolean()).as(setting.get("key").asText()).isTrue();
            assertThat(setting.get("value")).as(setting.get("key").asText()).isEqualTo(setting.get("defaultValue"));
        }

        var vat = setting(body, "vat.standard-rate");
        assertThat(vat.get("value").decimalValue()).isEqualByComparingTo("0.15");
        assertThat(vat.get("type").asText()).isEqualTo("DECIMAL");
        assertThat(vat.get("group").asText()).isEqualTo("VAT");
        assertThat(vat.get("sensitive").asBoolean()).isTrue();
        assertThat(vat.get("active").asBoolean()).isTrue();
        assertThat(vat.has("inactiveReason")).isFalse();
        assertThat(vat.get("limits").get("min").decimalValue()).isEqualByComparingTo("0");
        assertThat(vat.get("limits").get("max").decimalValue()).isEqualByComparingTo("0.9999");
        assertThat(vat.get("limits").get("scale").asInt()).isEqualTo(4);
        assertThat(vat.get("limits").get("nullable").asBoolean()).isFalse();

        var firstDay = setting(body, "reports.close-day.first-trading-day");
        assertThat(firstDay.get("type").asText()).isEqualTo("DATE");
        assertThat(firstDay.get("limits").has("min")).isFalse();
        assertThat(firstDay.get("limits").get("nullable").asBoolean()).isTrue();

        assertThat(setting(body, "pos.offline.max-offline-hours").get("limits").get("max").asInt()).isEqualTo(18);
        assertThat(setting(body, "dashboard.openingHours").get("value").asText()).isEqualTo("06:00-15:00");
        assertThat(setting(body, "dashboard.slowMeal.afterTime").get("value").asText()).isEqualTo("12:30");

        var cap = setting(body, "pos.offline.account-cap");
        assertThat(cap.get("active").asBoolean()).isFalse();
        assertThat(cap.get("inactiveReason").asText()).isEqualTo("OFFLINE_NOT_ENTITLED");
        assertThat(cap.get("value").decimalValue()).isEqualByComparingTo("150.00");
    }

    @Test
    void everyEndpointIsAdminOnly() throws Exception {
        getAs("/admin/settings", cashierToken).andExpect(status().isForbidden());
        putAs("{\"board.done-display-minutes\": 5}", cashierToken).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/settings/board.done-display-minutes/reset").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
        getAs("/admin/settings/history", cashierToken).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/settings")).andExpect(status().isUnauthorized());
        assertThat(siteSettings.boardDoneDisplayMinutes()).isEqualTo(10);
    }

    // ---- PUT -----------------------------------------------------------------------------------

    @Test
    void put_savesRecordsTheChangeAndAppliesAtOnce() throws Exception {
        var before = historyCount();
        var body = body(putAs("{\"pos.session.idle-lock-minutes\": 10, \"pos.session.idle-expiry-minutes\": 45}", adminToken)
                .andExpect(status().isOk()));

        var lock = setting(body, "pos.session.idle-lock-minutes");
        assertThat(lock.get("value").asInt()).isEqualTo(10);
        assertThat(lock.get("defaultValue").asInt()).isEqualTo(5);
        assertThat(lock.get("isDefault").asBoolean()).isFalse();
        assertThat(lock.get("lastChange").get("byName").asText()).isEqualTo(adminName);
        assertThat(lock.get("lastChange").get("action").asText()).isEqualTo("SET");
        assertThat(setting(body, "pos.session.idle-expiry-minutes").get("value").asInt()).isEqualTo(45);

        // No restart, no wait: this instance's cache was dropped when the save committed.
        assertThat(siteSettings.sessionIdleLockMinutes()).isEqualTo(10);
        assertThat(siteSettings.sessionIdleExpiryMinutes()).isEqualTo(45);

        assertThat(historyCount()).isEqualTo(before + 2);
        var entry = body(getAs("/admin/settings/history?key=pos.session.idle-lock-minutes&size=1", adminToken))
                .get("content").get(0);
        assertThat(entry.get("action").asText()).isEqualTo("SET");
        assertThat(entry.get("oldValue").asInt()).isEqualTo(5);
        assertThat(entry.get("oldIsDefault").asBoolean()).isTrue();
        assertThat(entry.get("newValue").asInt()).isEqualTo(10);
        assertThat(entry.get("newIsDefault").asBoolean()).isFalse();
        assertThat(entry.get("changedByName").asText()).isEqualTo(adminName);
    }

    @Test
    void put_decimalsAreKeptExactAndMoneyHasTwoDecimals() throws Exception {
        var body = body(putAs("{\"dashboard.variance.threshold\": 75.5, \"dashboard.voidRefund.watchPercent\": 4.25}", adminToken)
                .andExpect(status().isOk()));
        assertThat(setting(body, "dashboard.variance.threshold").get("value").decimalValue()).isEqualTo(new BigDecimal("75.50"));
        assertThat(setting(body, "dashboard.voidRefund.watchPercent").get("value").decimalValue()).isEqualTo(new BigDecimal("4.25"));
        assertThat(jdbc.queryForObject("SELECT value FROM site_settings WHERE setting_key = 'dashboard.variance.threshold'",
                String.class)).isEqualTo("75.50");
    }

    @Test
    void put_theCurrentValueRecordsNothingAndStaysDefault() throws Exception {
        var before = historyCount();
        var body = body(putAs("{\"board.done-display-minutes\": 10}", adminToken).andExpect(status().isOk()));
        assertThat(setting(body, "board.done-display-minutes").get("isDefault").asBoolean()).isTrue();
        assertThat(historyCount()).isEqualTo(before);
        putAs("{}", adminToken).andExpect(status().isOk());
        putAs("", adminToken).andExpect(status().isOk());
        assertThat(historyCount()).isEqualTo(before);
    }

    @Test
    void put_isAllOrNothingAndListsEveryBadKey() throws Exception {
        var before = historyCount();
        var errors = body(putAs("""
                {"pos.session.idle-lock-minutes": 10, "board.done-display-minutes": -1, "vat.standrad-rate": 0.15,
                 "pos.tills.require-registered": "yes", "dashboard.slowMeal.afterTime": "12:30:00",
                 "vat.standard-rate": 1, "pos.offline.account-cap": 10.555, "dashboard.openingHours": "15:00-06:00",
                 "dashboard.variance.threshold": null}""", adminToken).andExpect(status().isBadRequest()));

        assertThat(fieldNames(errors)).containsExactlyInAnyOrder("board.done-display-minutes", "vat.standrad-rate",
                "pos.tills.require-registered", "dashboard.slowMeal.afterTime", "vat.standard-rate", "pos.offline.account-cap",
                "dashboard.openingHours", "dashboard.variance.threshold");
        assertThat(errors.get("vat.standrad-rate").asText()).isEqualTo("Unknown setting.");
        assertThat(errors.get("board.done-display-minutes").asText()).isEqualTo("Must be a whole number between 0 and 1440.");
        assertThat(errors.get("pos.tills.require-registered").asText()).isEqualTo("Must be true or false.");
        assertThat(errors.get("dashboard.slowMeal.afterTime").asText()).isEqualTo("Must be a time like 12:30.");
        assertThat(errors.get("vat.standard-rate").asText()).isEqualTo("Must be between 0 and 0.9999 with at most 4 decimals.");
        assertThat(errors.get("dashboard.openingHours").asText()).isEqualTo("The start must be before the end.");
        assertThat(errors.get("dashboard.variance.threshold").asText()).isEqualTo("Is required.");

        // The valid idle-lock value wasn't saved either.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_settings", Integer.class)).isZero();
        assertThat(historyCount()).isEqualTo(before);
    }

    @Test
    void put_crossFieldRulesNameBothSettings() throws Exception {
        var errors = body(putAs("{\"pos.session.idle-lock-minutes\": 40}", adminToken).andExpect(status().isBadRequest()));
        assertThat(errors.get("pos.session.idle-lock-minutes").asText()).isEqualTo("Must be at most the idle expiry time (30).");
        assertThat(errors.get("pos.session.idle-expiry-minutes").asText()).isEqualTo("Must be at least the idle lock time (40).");

        errors = body(putAs("{\"pos.offline.warn-offline-hours\": 6}", adminToken).andExpect(status().isBadRequest()));
        assertThat(fieldNames(errors)).containsExactlyInAnyOrder("pos.offline.warn-offline-hours", "pos.offline.max-offline-hours");

        errors = body(putAs("{\"pos.offline.max-offline-hours\": 19}", adminToken).andExpect(status().isBadRequest()));
        assertThat(errors.get("pos.offline.max-offline-hours").asText()).isEqualTo("Must be a whole number between 1 and 18.");

        // Both together are fine.
        putAs("{\"pos.session.idle-lock-minutes\": 40, \"pos.session.idle-expiry-minutes\": 40}", adminToken)
                .andExpect(status().isOk());
    }

    @Test
    void put_notAJsonObjectIsRefused() throws Exception {
        var errors = body(putAs("[1, 2]", adminToken).andExpect(status().isBadRequest()));
        assertThat(errors.get("body").asText()).startsWith("Must be a JSON object");
        putAs("{not json", adminToken).andExpect(status().isBadRequest());
    }

    // ---- Sensitive settings ------------------------------------------------------------------

    @Test
    void sensitive_needsARecentPasswordSignIn() throws Exception {
        clock.setInstant(clock.instant().plus(Duration.ofMinutes(31)));

        // History written while the clock is moved ahead is dated in the future, so this test only
        // saves a key whose history order no other test checks.
        var error = body(putAs("{\"vat.standard-rate\": 0.16, \"pos.offline.account-cap\": 200, \"dashboard.slowMeal.thresholdPercent\": 30}",
                adminToken).andExpect(status().isForbidden()));
        assertThat(error.get("code").asText()).isEqualTo("REAUTH_REQUIRED");
        assertThat(error.get("error").asText())
                .isEqualTo("Sign in again with your password to change pos.offline.account-cap, vat.standard-rate.");
        assertThat(error.get("details").get("keys")).extracting(JsonNode::asText)
                .containsExactly("pos.offline.account-cap", "vat.standard-rate");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_settings", Integer.class)).isZero();

        // Unchanged sensitive values and non-sensitive settings don't need it.
        putAs("{\"vat.standard-rate\": 0.15, \"dashboard.slowMeal.thresholdPercent\": 30}", adminToken).andExpect(status().isOk());

        // Validation comes first: 400, not 403.
        putAs("{\"vat.standard-rate\": 2}", adminToken).andExpect(status().isBadRequest());
    }

    @Test
    void sensitive_withARecentPasswordSignInIsSaved() throws Exception {
        var body = body(putAs("{\"vat.standard-rate\": 0.16, \"pos.tills.require-registered\": true}", adminToken)
                .andExpect(status().isOk()));
        assertThat(setting(body, "vat.standard-rate").get("value").decimalValue()).isEqualByComparingTo("0.16");
        assertThat(siteSettings.vatStandardRate()).isEqualByComparingTo("0.16");
        assertThat(siteSettings.tillsRequireRegistered()).isTrue();
    }

    @Test
    void sensitive_resetNeedsItToo() throws Exception {
        putAs("{\"vat.standard-rate\": 0.16}", adminToken).andExpect(status().isOk());
        clock.setInstant(clock.instant().plus(Duration.ofMinutes(31)));
        var error = body(reset("vat.standard-rate").andExpect(status().isForbidden()));
        assertThat(error.get("code").asText()).isEqualTo("REAUTH_REQUIRED");
        assertThat(siteSettings.vatStandardRate()).isEqualByComparingTo("0.16");
    }

    // ---- Reset ---------------------------------------------------------------------------------

    @Test
    void reset_restoresTheDefaultAndRecordsIt() throws Exception {
        putAs("{\"board.done-display-minutes\": 3}", adminToken).andExpect(status().isOk());
        assertThat(siteSettings.boardDoneDisplayMinutes()).isEqualTo(3);
        var before = historyCount();

        var body = body(reset("board.done-display-minutes").andExpect(status().isOk()));
        var board = setting(body, "board.done-display-minutes");
        assertThat(board.get("isDefault").asBoolean()).isTrue();
        assertThat(board.get("value").asInt()).isEqualTo(10);
        assertThat(board.get("lastChange").get("action").asText()).isEqualTo("RESET");
        assertThat(siteSettings.boardDoneDisplayMinutes()).isEqualTo(10);

        var entry = body(getAs("/admin/settings/history?key=board.done-display-minutes&size=1", adminToken)).get("content").get(0);
        assertThat(entry.get("action").asText()).isEqualTo("RESET");
        assertThat(entry.get("oldValue").asInt()).isEqualTo(3);
        assertThat(entry.get("newValue").asInt()).isEqualTo(10);
        assertThat(entry.get("newIsDefault").asBoolean()).isTrue();

        // Already at the default: nothing recorded.
        reset("board.done-display-minutes").andExpect(status().isOk());
        assertThat(historyCount()).isEqualTo(before + 1);
    }

    @Test
    void reset_unknownKeyIs404() throws Exception {
        var error = body(reset("no.such.setting").andExpect(status().isNotFound()));
        assertThat(error.get("code").asText()).isEqualTo("UNKNOWN_SETTING");
    }

    @Test
    void reset_thatWouldBreakACrossFieldRuleIsRefused() throws Exception {
        putAs("{\"pos.session.idle-lock-minutes\": 40, \"pos.session.idle-expiry-minutes\": 45}", adminToken)
                .andExpect(status().isOk());
        var errors = body(reset("pos.session.idle-expiry-minutes").andExpect(status().isBadRequest()));
        assertThat(fieldNames(errors)).containsExactlyInAnyOrder("pos.session.idle-lock-minutes", "pos.session.idle-expiry-minutes");
        assertThat(siteSettings.sessionIdleExpiryMinutes()).isEqualTo(45);
    }

    // ---- History -------------------------------------------------------------------------------

    @Test
    void history_isNewestFirstPagedAndFiltered() throws Exception {
        putAs("{\"board.done-display-minutes\": 1}", adminToken).andExpect(status().isOk());
        putAs("{\"board.done-display-minutes\": 2}", adminToken).andExpect(status().isOk());
        putAs("{\"board.done-display-minutes\": 3}", adminToken).andExpect(status().isOk());

        var page = body(getAs("/admin/settings/history?key=board.done-display-minutes&size=2", adminToken).andExpect(status().isOk()));
        assertThat(page.get("size").asInt()).isEqualTo(2);
        assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(page.get("content")).extracting(entry -> entry.get("newValue").asInt()).containsExactly(3, 2);
        var second = body(getAs("/admin/settings/history?key=board.done-display-minutes&size=2&page=1", adminToken));
        assertThat(second.get("content").get(0).get("newValue").asInt()).isEqualTo(1);

        var all = body(getAs("/admin/settings/history?size=500", adminToken));
        assertThat(all.get("size").asInt()).isEqualTo(200);
        assertThat(body(getAs("/admin/settings/history?key=no.such.setting", adminToken)).get("content")).isEmpty();
    }

    // ---- Dashboard cache -----------------------------------------------------------------------

    @Test
    void aSaveClearsTheDashboardCache() throws Exception {
        var computed = new AtomicInteger();
        dashboardCache.get("t0-4a-test", 300, () -> computed.incrementAndGet());
        dashboardCache.get("t0-4a-test", 300, () -> computed.incrementAndGet());
        assertThat(computed.get()).isEqualTo(1);

        putAs("{\"dashboard.variance.threshold\": 80}", adminToken).andExpect(status().isOk());
        dashboardCache.get("t0-4a-test", 300, () -> computed.incrementAndGet());
        assertThat(computed.get()).isEqualTo(2);
    }

    // ---- First trading day (contract 4.2) ------------------------------------------------------

    @Test
    void firstTradingDay_rulesAgainstOrdersAndZReadings() throws Exception {
        ReportFixture.clear(jdbc);
        try {
            putAs("{\"reports.close-day.first-trading-day\": null}", adminToken).andExpect(status().isOk());
            assertThat(siteSettings.firstTradingDay()).isNull();

            // Sales from 5 Oct (a shift with an order, never closed); Z 1 for 7 Oct.
            var fixture = ReportFixture.empty(jdbc);
            var cashier = AuthTestHelper.cashierAId(mockMvc);
            var admin = AuthTestHelper.adminId(mockMvc);
            var oct5 = LocalDate.of(2026, 10, 5);
            var oct7 = LocalDate.of(2026, 10, 7);
            var shift = fixture.closedShift(cashier, "500.00", ReportFixture.utc(oct5, 7, 0), ReportFixture.utc(oct5, 15, 0),
                    "500.00", "500.00", "0.00", null, null, admin, "0.00", "0.00");
            fixture.order(shift, cashier, oct5, 1, 9, 0, "50.00", "50.00", "COLLECTED");
            fixture.closedShift(cashier, "500.00", ReportFixture.utc(oct7, 7, 0), ReportFixture.utc(oct7, 15, 0),
                    "500.00", "500.00", "0.00", null, null, admin, "0.00", "0.00");
            jdbc.update("""
                    INSERT INTO z_readings (z_number, trading_day, closed_by, definition_version, gross_sales, net_sales, snapshot)
                    VALUES (1, ?, ?, 1, 0, 0, '{}')""", java.sql.Date.valueOf(oct7), admin);

            var errors = body(putFirstTradingDay("\"2026-10-08\"").andExpect(status().isBadRequest()));
            assertThat(errors.get("reports.close-day.first-trading-day").asText())
                    .isEqualTo("Must be on or before 7 Oct 2026, the first day with a Z reading.");
            errors = body(putFirstTradingDay("\"2026-10-04\"").andExpect(status().isBadRequest()));
            assertThat(errors.get("reports.close-day.first-trading-day").asText())
                    .isEqualTo("Must be on or after 5 Oct 2026, the first day with sales.");
            putFirstTradingDay("\"2026-10-06\"").andExpect(status().isOk());   // nothing to resolve
            errors = body(putFirstTradingDay("\"2026-10-05\"").andExpect(status().isBadRequest()));
            assertThat(errors.get("reports.close-day.first-trading-day").asText())
                    .isEqualTo("Must be after 5 Oct 2026, a trading day before the first Z reading that was never closed.");
            putFirstTradingDay("\"2026-10-07\"").andExpect(status().isOk());
            assertThat(siteSettings.firstTradingDay()).isEqualTo(oct7);
        } finally {
            ReportFixture.clear(jdbc);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private ResultActions putFirstTradingDay(String json) throws Exception {
        return putAs("{\"reports.close-day.first-trading-day\": " + json + "}", adminToken);
    }

    private ResultActions getAs(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    private ResultActions putAs(String json, String token) throws Exception {
        return mockMvc.perform(put("/admin/settings").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions reset(String key) throws Exception {
        return mockMvc.perform(post("/admin/settings/" + key + "/reset").header("Authorization", "Bearer " + adminToken));
    }

    private long historyCount() {
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM site_setting_changes", Long.class);
        return count == null ? 0 : count;
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static JsonNode setting(JsonNode body, String key) {
        for (var setting : body.get("settings")) {
            if (setting.get("key").asText().equals(key)) {
                return setting;
            }
        }
        throw new AssertionError("No setting " + key);
    }

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
