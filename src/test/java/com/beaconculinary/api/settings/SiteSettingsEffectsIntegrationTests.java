package com.beaconculinary.api.settings;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.dashboard.DashboardSettings;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.offline.OfflineProperties;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.O3Tills.Cashier;
import com.beaconculinary.api.support.O3Tills.Till;
import com.beaconculinary.api.support.PosFlowHelper;
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
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.beaconculinary.api.support.O3Tills.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * T0-4a: each moved setting through the code that uses it. With nothing saved the configuration
 * default applies; a value saved through PUT /admin/settings takes effect on the next request, with
 * no restart; a reset brings the default back. Also: a VAT change applies to new orders only and to
 * the offline snapshot, and another instance's save is seen after the 30 s cache lifetime.
 *
 * <p>Saves of sensitive settings need a password sign-in within 30 minutes on the application clock,
 * so every test starts with the clock at the real time ({@link #realTime()}); the VAT test sells at
 * 12:30 (lunch) and moves the clock back to the real time for its sensitive save and reset.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class SiteSettingsEffectsIntegrationTests {
    private static final String PREFIX = "T04a Effects ";
    private static final LocalTime LUNCH = LocalTime.of(12, 30);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private SiteSettings siteSettings;
    @Autowired private DashboardSettings dashboardSettings;
    @Autowired private OfflineProperties offlineProperties;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired private AuthorizationTokenRepository authorizationTokenRepository;
    @Autowired private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired private MealCatalogRepository mealCatalogRepository;
    @Autowired private ComponentCatalogRepository componentCatalogRepository;

    private String adminPwd;
    private O3Tills tills;
    private Cashier cashier;
    private Till t1;

    @BeforeEach
    void setUp() throws Exception {
        realTime();
        SiteSettingsFixture.clearSavedAndHistory(jdbc);
        siteSettings.invalidate();
        offlineProperties.setEnabled(true);
        adminPwd = AuthTestHelper.loginAsAdmin(mockMvc);
        tills = new O3Tills(mockMvc, jdbc, PREFIX, adminPwd);
        cashier = tills.createCashier();
        t1 = tills.registerTill();
    }

    @AfterEach
    void tearDown() {
        offlineProperties.setEnabled(false);
        SiteSettingsFixture.clearSavedAndHistory(jdbc);
        siteSettings.invalidate();
        jdbc.update("DELETE FROM [receipt_reprints]");
        orderAdjustmentRepository.deleteAll();
        authorizationTokenRepository.deleteAll();
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        tills.cleanUp();
        clock.reset();
    }

    // ---- each setting: default, saved, reset ---------------------------------------------------

    @Test
    void boardDoneDisplayMinutes_onThePublicBoard() throws Exception {
        assertThat(board().get("doneDisplayMinutes").asInt()).isEqualTo(10);
        save("{\"board.done-display-minutes\": 3}");
        assertThat(board().get("doneDisplayMinutes").asInt()).isEqualTo(3);
        reset("board.done-display-minutes");
        assertThat(board().get("doneDisplayMinutes").asInt()).isEqualTo(10);
    }

    @Test
    void tillAndOfflineLimits_inTheTillStatus() throws Exception {
        var status = tillStatus();
        assertThat(status.get("idleLockMinutes").asInt()).isEqualTo(5);
        assertThat(status.get("requireRegistered").asBoolean()).isFalse();
        assertThat(status.get("maxOfflineHours").asInt()).isEqualTo(6);
        assertThat(status.get("warnOfflineHours").asInt()).isEqualTo(5);

        save("{\"pos.session.idle-lock-minutes\": 7, \"pos.tills.require-registered\": true,"
                + " \"pos.offline.max-offline-hours\": 8, \"pos.offline.warn-offline-hours\": 7}");
        status = tillStatus();
        assertThat(status.get("idleLockMinutes").asInt()).isEqualTo(7);
        assertThat(status.get("requireRegistered").asBoolean()).isTrue();
        assertThat(status.get("maxOfflineHours").asInt()).isEqualTo(8);
        assertThat(status.get("warnOfflineHours").asInt()).isEqualTo(7);

        for (var key : List.of("pos.session.idle-lock-minutes", "pos.tills.require-registered",
                "pos.offline.warn-offline-hours", "pos.offline.max-offline-hours")) {
            reset(key);
        }
        status = tillStatus();
        assertThat(status.get("idleLockMinutes").asInt()).isEqualTo(5);
        assertThat(status.get("requireRegistered").asBoolean()).isFalse();
        assertThat(status.get("maxOfflineHours").asInt()).isEqualTo(6);
        assertThat(status.get("warnOfflineHours").asInt()).isEqualTo(5);
    }

    @Test
    void requireRegistered_refusesPinSignInOnAnUnregisteredBrowser() throws Exception {
        assertThat(pinLoginWithoutTill().getResponse().getStatus()).isEqualTo(200);

        save("{\"pos.tills.require-registered\": true}");
        var refused = pinLoginWithoutTill();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(refused).get("code").asText()).isEqualTo("DEVICE_NOT_REGISTERED");

        reset("pos.tills.require-registered");
        assertThat(pinLoginWithoutTill().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void idleExpiry_endsTheOwnersSessionSooner() throws Exception {
        var start = clock.instant();
        openShiftOnT1();
        clock.setInstant(start.plus(Duration.ofMinutes(3)));
        assertThat(tillStatus().get("shift").get("locked").asBoolean()).isFalse();   // default: 30 minutes

        save("{\"pos.session.idle-lock-minutes\": 1, \"pos.session.idle-expiry-minutes\": 2}");
        assertThat(tillStatus().get("shift").get("locked").asBoolean()).isTrue();

        reset("pos.session.idle-expiry-minutes");
        assertThat(tillStatus().get("shift").get("locked").asBoolean()).isFalse();
    }

    @Test
    void offlineSettings_inTheGrantAndTheSnapshot() throws Exception {
        var token = openShiftOnT1();
        var grant = grant(token);
        assertThat(grant.get("maxOfflineHours").asInt()).isEqualTo(6);
        assertThat(grant.get("warnOfflineHours").asInt()).isEqualTo(5);
        assertThat(grant.get("accountCap").decimalValue()).isEqualByComparingTo("150.00");
        var snapshot = snapshot(token);
        assertThat(snapshot.get("accountCap").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(snapshot.get("limits").get("maxOfflineHours").asInt()).isEqualTo(6);

        save("{\"pos.offline.account-cap\": 200, \"pos.offline.max-offline-hours\": 8, \"pos.offline.warn-offline-hours\": 7}");
        grant = grant(token);
        assertThat(grant.get("maxOfflineHours").asInt()).isEqualTo(8);
        assertThat(grant.get("warnOfflineHours").asInt()).isEqualTo(7);
        assertThat(grant.get("accountCap").decimalValue()).isEqualByComparingTo("200.00");
        snapshot = snapshot(token);
        assertThat(snapshot.get("accountCap").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(snapshot.get("limits").get("maxOfflineHours").asInt()).isEqualTo(8);
        assertThat(snapshot.get("limits").get("warnOfflineHours").asInt()).isEqualTo(7);

        save("{\"pos.offline.accounts-enabled\": false}");
        grant = grant(token);
        assertThat(grant.get("accountsEnabled").asBoolean()).isFalse();
        assertThat(grant.has("accountCap")).isFalse();
        snapshot = snapshot(token);
        assertThat(snapshot.get("accountsEnabled").asBoolean()).isFalse();
        assertThat(snapshot.has("accountCap")).isFalse();
        assertThat(texts(snapshot.get("tenders"))).containsExactly("CASH", "CARD");

        for (var key : List.of("pos.offline.accounts-enabled", "pos.offline.account-cap", "pos.offline.warn-offline-hours",
                "pos.offline.max-offline-hours")) {
            reset(key);
        }
        snapshot = snapshot(token);
        assertThat(snapshot.get("accountsEnabled").asBoolean()).isTrue();
        assertThat(snapshot.get("accountCap").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(snapshot.get("limits").get("maxOfflineHours").asInt()).isEqualTo(6);
    }

    @Test
    void vat_appliesToNewOrdersOnly_andToTheOfflineSnapshot() throws Exception {
        // Sales happen at 12:30 (lunch); the sensitive VAT save and reset at the real time.
        clock.setTime(LUNCH);
        var token = openShiftOnT1();
        var stew = stewOption();
        assertThat(snapshot(token).get("vatRate").decimalValue()).isEqualByComparingTo("0.15");

        var before = placeOrder(token, stew);
        assertThat(vat(before)).containsExactly(new BigDecimal("0.1500"), new BigDecimal("6.52"));

        realTime();
        save("{\"vat.standard-rate\": 0.16}");
        clock.setTime(LUNCH);
        assertThat(body(mockMvc.perform(get("/admin/settings/vat").header("Authorization", "Bearer " + adminPwd)).andReturn())
                .get("standardRate").decimalValue()).isEqualByComparingTo("0.16");
        assertThat(snapshot(token).get("vatRate").decimalValue()).isEqualByComparingTo("0.16");
        var after = placeOrder(token, stew);
        assertThat(vat(after)).containsExactly(new BigDecimal("0.1600"), new BigDecimal("6.90"));
        assertThat(vat(before)).containsExactly(new BigDecimal("0.1500"), new BigDecimal("6.52"));

        realTime();
        reset("vat.standard-rate");
        assertThat(vat(placeOrder(token, stew))).containsExactly(new BigDecimal("0.1500"), new BigDecimal("6.52"));
    }

    @Test
    void firstTradingDay_onTheCloseDaySettings() throws Exception {
        ReportFixture.clear(jdbc);   // no orders or Z readings, so any date may be saved
        try {
            var configured = closeDaySetting();
            save("{\"reports.close-day.first-trading-day\": \"2026-11-02\"}");
            assertThat(closeDaySetting()).isEqualTo("2026-11-02");
            save("{\"reports.close-day.first-trading-day\": null}");
            assertThat(closeDaySetting()).isNull();
            reset("reports.close-day.first-trading-day");
            assertThat(closeDaySetting()).isEqualTo(configured);
        } finally {
            ReportFixture.clear(jdbc);
        }
    }

    @Test
    void dashboardThresholdsAndHours() throws Exception {
        assertThat(dashboardSettings.varianceThreshold()).isEqualByComparingTo("50.00");
        assertThat(dashboardSettings.voidRefundWatchPercent()).isEqualByComparingTo("3.0");
        assertThat(dashboardSettings.slowMealThresholdPercent()).isEqualByComparingTo("25");
        assertThat(dashboardSettings.slowMealAfter()).isEqualTo(LocalTime.of(12, 30));
        assertThat(dashboardSettings.firstHour()).isEqualTo(6);
        assertThat(dashboardSettings.lastHour()).isEqualTo(14);

        save("{\"dashboard.variance.threshold\": 80, \"dashboard.voidRefund.watchPercent\": 5,"
                + " \"dashboard.slowMeal.thresholdPercent\": 40, \"dashboard.slowMeal.afterTime\": \"13:00\","
                + " \"dashboard.openingHours\": \"07:00-16:00\"}");
        assertThat(dashboardSettings.varianceThreshold()).isEqualByComparingTo("80.00");
        assertThat(dashboardSettings.voidRefundWatchPercent()).isEqualByComparingTo("5");
        assertThat(dashboardSettings.slowMealThresholdPercent()).isEqualByComparingTo("40");
        assertThat(dashboardSettings.slowMealAfter()).isEqualTo(LocalTime.of(13, 0));
        assertThat(dashboardSettings.firstHour()).isEqualTo(7);
        assertThat(dashboardSettings.lastHour()).isEqualTo(15);

        for (var key : List.of("dashboard.variance.threshold", "dashboard.voidRefund.watchPercent",
                "dashboard.slowMeal.thresholdPercent", "dashboard.slowMeal.afterTime", "dashboard.openingHours")) {
            reset(key);
        }
        assertThat(dashboardSettings.varianceThreshold()).isEqualByComparingTo("50.00");
        assertThat(dashboardSettings.slowMealAfter()).isEqualTo(LocalTime.of(12, 30));
        assertThat(dashboardSettings.firstHour()).isEqualTo(6);
        assertThat(dashboardSettings.lastHour()).isEqualTo(14);
    }

    // ---- the cache -----------------------------------------------------------------------------

    @Test
    void anotherInstancesSave_isSeenWithinThirtySeconds() throws Exception {
        var start = clock.instant();
        siteSettings.invalidate();
        assertThat(board().get("doneDisplayMinutes").asInt()).isEqualTo(10);   // cached at start

        // Another instance saves: the row changes, but this instance's cache wasn't told.
        jdbc.update("INSERT INTO [site_settings] ([setting_key], [value], [updated_at], [updated_by], [updated_by_name])"
                + " VALUES ('board.done-display-minutes', '3', SYSUTCDATETIME(), 1, 'Other instance')");

        clock.setInstant(start.plusSeconds(29));
        assertThat(board().get("doneDisplayMinutes").asInt()).isEqualTo(10);
        clock.setInstant(start.plusSeconds(30));
        assertThat(board().get("doneDisplayMinutes").asInt()).isEqualTo(3);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The application clock at the real time, so a password sign-in counts as recent. */
    private void realTime() {
        clock.setInstant(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private void save(String json) throws Exception {
        var result = mockMvc.perform(put("/admin/settings").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
    }

    private void reset(String key) throws Exception {
        var result = mockMvc.perform(post("/admin/settings/" + key + "/reset").header("Authorization", "Bearer " + adminPwd))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(key + ": " + result.getResponse().getContentAsString()).isEqualTo(200);
    }

    private JsonNode board() throws Exception {
        return body(mockMvc.perform(get("/public/board/today")).andReturn());
    }

    private JsonNode tillStatus() throws Exception {
        var result = mockMvc.perform(on(get("/pos/till-status"), t1)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result);
    }

    private MvcResult pinLoginWithoutTill() throws Exception {
        return mockMvc.perform(post("/auth/pin-login").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("cashierId", cashier.id(), "pin", cashier.pin())))).andReturn();
    }

    /** The cashier signs in on T1 and opens a shift there; returns their token. */
    private String openShiftOnT1() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        tills.openShift(token, t1);
        return token;
    }

    private JsonNode grant(String token) throws Exception {
        var result = mockMvc.perform(on(post("/pos/offline/grant"), t1).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result);
    }

    private JsonNode snapshot(String token) throws Exception {
        var result = mockMvc.perform(on(get("/pos/offline/snapshot"), t1).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    private String closeDaySetting() throws Exception {
        var value = body(mockMvc.perform(get("/admin/settings/close-day").header("Authorization", "Bearer " + adminPwd))
                .andReturn()).get("firstTradingDay");
        return value == null || value.isNull() ? null : value.asText();
    }

    /** A 50.00 lunch stew (meals can't be planned under All Day), sold at 12:30. */
    private long stewOption() throws Exception {
        clock.setTime(LUNCH);
        var pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var beef = pos.createComponent("T04a Beef", "15.00");
        return pos.createDailyOption(pos.periodId("lunch"), pos.createMealCatalog("T04a Stew", "50.00", beef), 20);
    }

    /** One stew, paid in cash, rung on T1 at 12:30; returns the order id. */
    private long placeOrder(String token, long stewOptionId) throws Exception {
        clock.setTime(LUNCH);
        var result = mockMvc.perform(on(post("/orders"), t1).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "clientOrderId", UUID.randomUUID().toString(),
                        "payments", List.of(Map.of("method", "CASH", "amount", new BigDecimal("50.00"),
                                "amountTendered", new BigDecimal("50.00"))),
                        "lines", List.of(Map.of("dailyMealOptionId", stewOptionId, "quantity", 1, "extras", List.of())))))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    /** The VAT rate and amount stored on an order. */
    private List<BigDecimal> vat(long orderId) {
        var row = jdbc.queryForMap("SELECT [vat_rate], [vat_included_amount] FROM [orders] WHERE [id] = ?", orderId);
        return List.of((BigDecimal) row.get("vat_rate"), (BigDecimal) row.get("vat_included_amount"));
    }

    private static List<String> texts(JsonNode array) {
        var texts = new ArrayList<String>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

}
