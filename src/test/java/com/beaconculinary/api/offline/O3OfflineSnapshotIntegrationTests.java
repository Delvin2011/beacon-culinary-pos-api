package com.beaconculinary.api.offline;

import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.CatalogTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.O3Tills.Cashier;
import com.beaconculinary.api.support.O3Tills.Till;
import com.beaconculinary.api.support.PosFlowHelper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.beaconculinary.api.support.O3Tills.body;
import static com.beaconculinary.api.support.O3Tills.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** O3: GET /pos/offline/snapshot (contract section 8). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O3OfflineSnapshotIntegrationTests {
    private static final String PREFIX = "O3 Snap ";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EMAIL = "o3snap.finance@example.test";
    private static final String PHONE = "021 555 0199";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private OfflineProperties properties;
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

    private O3Tills tills;
    private String adminPwd;
    private Cashier cashier;
    private Till t1;
    private Till t2;
    private long stewOptionId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        properties.setEnabled(true);
        adminPwd = AuthTestHelper.loginAsAdmin(mockMvc);
        tills = new O3Tills(mockMvc, jdbc, PREFIX, adminPwd);
        cashier = tills.createCashier();
        t1 = tills.registerTill();
        t2 = tills.registerTill();

        var pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var beef = pos.createComponent("O3 Extra Beef", "15.00");
        var lunch = pos.periodId("lunch");
        stewOptionId = pos.createDailyOption(lunch, pos.createMealCatalog("O3 Stew", "50.00", beef), 40);
        pos.createDailyOption(pos.periodId("breakfast"), pos.createMealCatalog("O3 Porridge", "30.00", beef), 20);
        var curry = pos.createDailyOption(lunch, pos.createMealCatalog("O3 Curry", "55.00", beef), 10);
        jdbc.update("UPDATE [daily_meal_options] SET [status] = 'PLANNED' WHERE [id] = ?", curry);
        pos.createDailyComponentStock(lunch, beef, 9);
        mockMvc.perform(post("/admin/meal-catalog").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", "O3 Water", "price", "20.00",
                        "categoryId", CatalogTestHelper.categoryId(mockMvc, "Beverages > Cold beverages"))))).andReturn();
        var account = mockMvc.perform(post("/admin/accounts").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", PREFIX + "Finance", "department", "Finance", "contactNumber", PHONE, "contactEmail", EMAIL))))
                .andReturn();
        assertThat(account.getResponse().getStatus()).as(account.getResponse().getContentAsString()).isEqualTo(201);
    }

    @AfterEach
    void tearDown() {
        properties.setEnabled(false);
        properties.setAccountsEnabled(true);
        jdbc.update("DELETE FROM [accounts] WHERE [name] LIKE ?", PREFIX + "%");
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        tills.cleanUp();
        clock.reset();
    }

    // ---- contents ----

    @Test
    void listsEveryPeriodOfToday_beverages_extras_andTheSettings() throws Exception {
        var result = snapshot(tills.pinLogin(cashier, t1), t1, null);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        var body = body(result);
        assertThat(result.getResponse().getHeader("ETag")).isEqualTo("\"" + body.get("snapshotVersion").asText() + "\"");
        assertThat(body.get("tradingDay").asText()).isEqualTo(LocalDate.now(clock).toString());
        assertThat(body.get("generatedAt").asText()).endsWith("Z");
        assertThat(body.get("serverTimeUtc").asText()).endsWith("Z");

        var periodNames = names(body.get("periods"), "name");
        assertThat(periodNames).anyMatch(name -> name.equalsIgnoreCase("breakfast")).anyMatch(name -> name.equalsIgnoreCase("lunch"));
        assertThat(body.get("periods").get(0).has("startTime")).isTrue();

        // Breakfast isn't open at 12:30, so GET /menu/today leaves it out; the snapshot has every period.
        var menuToday = MAPPER.readTree(mockMvc.perform(get("/menu/today")).andReturn().getResponse().getContentAsString());
        assertThat(names(menuToday.get("options"), "name")).doesNotContain("O3 Porridge").contains("O3 Stew");
        var porridge = option(body, "O3 Porridge");
        assertThat(porridge.get("status").asText()).isEqualTo("READY");
        assertThat(porridge.get("periodId").isNull()).isFalse();

        var stew = option(body, "O3 Stew");
        assertThat(stew.get("id").asLong()).isEqualTo(stewOptionId);
        assertThat(stew.get("price").decimalValue()).isEqualByComparingTo(menuOption(menuToday, "O3 Stew").get("price").decimalValue());
        assertThat(stew.get("station").asText()).isEqualTo("KITCHEN");
        assertThat(stew.get("group").asText()).isEqualTo("SET_MEALS");
        assertThat(stew.get("plannedPortions").asInt()).isEqualTo(40);
        assertThat(stew.get("portionsRemaining").asInt()).isEqualTo(menuOption(menuToday, "O3 Stew").get("portionsRemaining").asInt());
        assertThat(stew.get("outToday").asBoolean()).isFalse();
        assertThat(stew.get("alwaysOn").asBoolean()).isFalse();

        assertThat(option(body, "O3 Curry").get("status").asText()).isEqualTo("PLANNED");

        var water = option(body, "O3 Water");
        assertThat(water.get("periodId").isNull()).isTrue();
        assertThat(water.get("group").asText()).isEqualTo("BEVERAGES");
        assertThat(water.get("alwaysOn").asBoolean()).isTrue();
        assertThat(water.get("portionsRemaining").isNull()).isTrue();

        var extra = find(body.get("extras"), "componentName", "O3 Extra Beef");
        assertThat(extra.get("priceDelta").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(extra.get("bufferRemaining").asInt()).isEqualTo(9);
        assertThat(extra.get("periodId").asLong()).isEqualTo(stew.get("periodId").asLong());

        assertThat(body.get("vatRate").decimalValue()).isEqualByComparingTo("0.15");
        assertThat(body.get("receiptSettings").has("showVatLine")).isTrue();
        assertThat(body.get("receiptSettings").has("updatedBy")).isFalse();
        assertThat(names(body.get("tenders"), null)).containsExactly("CASH", "CARD", "ACCOUNT");
        assertThat(body.get("accountsEnabled").asBoolean()).isTrue();
        assertThat(body.get("accountCap").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(body.get("limits").get("maxOfflineHours").asInt()).isEqualTo(6);
        assertThat(body.get("limits").get("warnOfflineHours").asInt()).isEqualTo(5);

        var size = result.getResponse().getContentAsByteArray().length;
        System.out.println("O3_SNAPSHOT_SIZE_BYTES=" + size + " options=" + body.get("options").size()
                + " accounts=" + body.get("accounts").size());
        assertThat(size).isLessThan(200_000);
    }

    @Test
    void accounts_carryIdNumberAndNameOnly() throws Exception {
        var result = snapshot(tills.pinLogin(cashier, t1), t1, null);
        var account = find(body(result).get("accounts"), "name", PREFIX + "Finance");

        assertThat(fieldNames(account)).containsExactly("id", "accountNumber", "name");
        assertThat(account.get("accountNumber").asText()).startsWith("PM");
        var text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).doesNotContain(EMAIL).doesNotContain(PHONE).doesNotContain("contactEmail").doesNotContain("contactNumber");
    }

    @Test
    void withAccountsOff_accountsAndTheCapAreLeftOut() throws Exception {
        properties.setAccountsEnabled(false);

        var body = body(snapshot(tills.pinLogin(cashier, t1), t1, null));

        assertThat(body.get("accountsEnabled").asBoolean()).isFalse();
        assertThat(body.has("accounts")).isFalse();
        assertThat(body.has("accountCap")).isFalse();
        assertThat(names(body.get("tenders"), null)).containsExactly("CASH", "CARD");
    }

    // ---- ETag ----

    @Test
    void anUnchangedSnapshot_returns304_andAChangeGivesANewVersion() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        var first = snapshot(token, t1, null);
        var etag = first.getResponse().getHeader("ETag");

        var again = snapshot(token, t1, null);
        assertThat(again.getResponse().getHeader("ETag")).isEqualTo(etag);

        var notModified = snapshot(token, t1, etag);
        assertThat(notModified.getResponse().getStatus()).isEqualTo(304);
        assertThat(notModified.getResponse().getContentAsString()).isEmpty();
        assertThat(notModified.getResponse().getHeader("ETag")).isEqualTo(etag);
        assertThat(snapshot(token, t1, "W/" + etag + ", \"other\"").getResponse().getStatus()).isEqualTo(304);

        jdbc.update("UPDATE [daily_meal_options] SET [portions_remaining] = [portions_remaining] - 1 WHERE [id] = ?", stewOptionId);
        var changed = snapshot(token, t1, etag);
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(changed.getResponse().getHeader("ETag")).isNotEqualTo(etag);
    }

    @Test
    void crossOrigin_theTillCanReadTheETag_andSendIfNoneMatch() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        var result = mockMvc.perform(on(get("/pos/offline/snapshot"), t1).header("Authorization", "Bearer " + token)
                .header("Origin", "http://localhost:3000")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
        assertThat(result.getResponse().getHeader("Access-Control-Expose-Headers")).contains("ETag");

        var preflight = mockMvc.perform(options("/pos/offline/snapshot").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization, if-none-match")).andReturn();
        assertThat(preflight.getResponse().getStatus()).isEqualTo(200);
        assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("if-none-match");
    }

    // ---- trading day ----

    @Test
    void theTradingDay_isJohannesburgsDate_aroundMidnight() throws Exception {
        clock.setInstant(Instant.parse("2026-11-18T21:59:00Z"));
        var token = tills.pinLogin(cashier, t1);
        assertThat(body(snapshot(token, t1, null)).get("tradingDay").asText()).isEqualTo("2026-11-18");

        clock.setInstant(Instant.parse("2026-11-18T22:01:00Z"));
        assertThat(body(snapshot(token, t1, null)).get("tradingDay").asText()).isEqualTo("2026-11-19");
    }

    // ---- who may call ----

    @Test
    void anyPosUserOnTheTill_mayRefresh_withoutAShift() throws Exception {
        assertThat(snapshot(tills.adminLogin(t1), t1, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(snapshot(tills.pinLogin(cashier, t1), t1, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void refusals() throws Exception {
        var token = tills.pinLogin(cashier, t1);

        assertError(snapshot(token, t2, null), 403, "POS_SESSION_REQUIRED");
        assertError(snapshot(tills.pinLogin(cashier, null), null, null), 403, "DEVICE_NOT_REGISTERED");
        assertThat(mockMvc.perform(on(get("/pos/offline/snapshot"), t1)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(snapshot(AuthTestHelper.loginAsKitchen(mockMvc), t1, null).getResponse().getStatus()).isEqualTo(403);

        properties.setEnabled(false);
        assertError(snapshot(tills.pinLogin(cashier, t1), t1, null), 403, "OFFLINE_DISABLED");
    }

    // ---- helpers ----

    private MvcResult snapshot(String token, Till till, String ifNoneMatch) throws Exception {
        var request = on(get("/pos/offline/snapshot"), till).header("Authorization", "Bearer " + token);
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(body(result).get("code").asText()).isEqualTo(code);
    }

    private static JsonNode option(JsonNode snapshot, String name) {
        return find(snapshot.get("options"), "name", name);
    }

    private static JsonNode menuOption(JsonNode menu, String name) {
        return find(menu.get("options"), "name", name);
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (var node : array) {
            if (value.equals(node.get(field).asText())) {
                return node;
            }
        }
        throw new AssertionError(value + " not found in " + array);
    }

    private static List<String> names(JsonNode array, String field) {
        var names = new ArrayList<String>();
        array.forEach(node -> names.add(field == null ? node.asText() : node.get(field).asText()));
        return names;
    }

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
