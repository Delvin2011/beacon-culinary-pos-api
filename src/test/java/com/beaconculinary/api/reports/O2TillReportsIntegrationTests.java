package com.beaconculinary.api.reports;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.endofday.DayCloseRules;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.reports.definitions.CashUpVarianceReport;
import com.beaconculinary.api.reports.definitions.ZReadingReport;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.PosFlowHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O2 step 8 (R13, R14): the till on shift-based reports, the per-till section of the X and Z
 * readings (Z definition version 2), uncounted shifts shown blank rather than 0.00, Close Day's
 * blocker labels, and the admin shift views.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O2TillReportsIntegrationTests {
    private static final String PREFIX = "O2 Rep ";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private EndOfDayStatements statements;
    @Autowired
    private CashUpVarianceReport cashUp;
    @Autowired
    private ZReadingReport zReading;
    @Autowired
    private DayCloseRules dayCloseRules;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired
    private OrderStatusEventRepository orderStatusEventRepository;
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

    private PosFlowHelper pos;
    private String adminPwd;
    private String cashierA;
    private String cashierB;
    private long optionId;
    private Till t1;
    private Till t2;

    record Till(String code, String name, Cookie cookie) {
        String label() {
            return code + " · " + name;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        adminPwd = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierA = AuthTestHelper.loginAsCashier(mockMvc);
        cashierB = AuthTestHelper.loginWithPin(mockMvc, AuthTestHelper.cashierBId(mockMvc), "654321");
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var mealId = pos.createMealCatalog("O2 Rep Meal", "50.00", pos.createComponent("O2 Rep Beef", "15.00"));
        optionId = pos.createDailyOption(pos.periodId("lunch"), mealId, 100);
        t1 = registerTill();
        t2 = registerTill();
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
        jdbc.execute("DISABLE TRIGGER trg_shift_cash_count_events_insert_only ON shift_cash_count_events; "
                + "DELETE FROM shift_cash_count_events; "
                + "ENABLE TRIGGER trg_shift_cash_count_events_insert_only ON shift_cash_count_events;");
        jdbc.update("DELETE FROM [shift_signoffs]");
        jdbc.update("DELETE FROM [shifts]");
        jdbc.update("DELETE c FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id] WHERE d.[name] LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM [devices] WHERE [name] LIKE ?", PREFIX + "%");
        clock.reset();
    }

    @Test
    void theDayReading_namesEachShiftsTill_andTotalsEachTill_withRefundsOfOtherTillsSales() throws Exception {
        var s1 = openShift(cashierA, t1, "500.00");
        var s2 = openShift(cashierB, t2, "300.00");
        var orderId = sell(cashierA, t1);
        pos.markDone(orderId);
        refund(cashierB, t2, orderId);

        var reading = statements.dayReading(today(), null);

        var shifts = rows(section(reading, "shifts"));
        assertThat(row(shifts, "shiftId", s1).get("till")).isEqualTo(t1.label());
        assertThat(row(shifts, "shiftId", s2).get("till")).isEqualTo(t2.label());
        var tills = rows(section(reading, "tills"));
        var onT1 = row(tills, "till", t1.label());
        assertThat(onT1.get("shifts")).isEqualTo(1L);
        assertThat((BigDecimal) onT1.get("cashSales")).isEqualByComparingTo("50.00");
        assertThat((BigDecimal) onT1.get("refundsOfOtherTills")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) onT1.get("expectedCash")).isEqualByComparingTo("550.00");
        var onT2 = row(tills, "till", t2.label());
        assertThat((BigDecimal) onT2.get("cashRefunds")).isEqualByComparingTo("50.00");
        assertThat((BigDecimal) onT2.get("refundsOfOtherTills")).isEqualByComparingTo("50.00");
        assertThat((BigDecimal) onT2.get("expectedCash")).isEqualByComparingTo("250.00");
        assertThat(onT2.get("countedCash")).as("no counted shift on T2 yet").isNull();
    }

    @Test
    void anUncountedShift_showsBlank_notZero_everywhere() throws Exception {
        var shiftId = openShift(cashierA, t1, "500.00");
        assertThat(mockMvc.perform(post("/admin/shifts/" + shiftId + "/force-close").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Drawer locked away\"}")).andReturn()
                .getResponse().getStatus()).isEqualTo(200);

        var dayRow = row(rows(section(statements.dayReading(today(), null), "shifts")), "shiftId", shiftId);
        assertThat(dayRow.get("countedCash")).isNull();
        assertThat(dayRow.get("variance")).isNull();
        var tillRow = row(rows(section(statements.dayReading(today(), null), "tills")), "till", t1.label());
        assertThat(tillRow.get("uncounted")).isEqualTo(1L);
        assertThat(tillRow.get("countedCash")).isNull();

        var params = new HashMap<String, Object>(Map.of("from", today(), "to", today(), "signoff", "ALL"));
        var cashUpRow = row(cashUp.run(ReportParams.of(params)).rows(), "shiftId", shiftId);
        assertThat(cashUpRow.get("till")).isEqualTo(t1.label());
        assertThat(cashUpRow.get("countedCash")).isNull();
        assertThat(cashUpRow.get("variance")).isNull();
        assertThat(cashUpRow.get("reason")).isEqualTo("Not counted");

        var cashier = statements.cashierReading(shiftId);
        assertThat(item(section(cashier, "cashUp"), "Counted cash")).isNull();
        assertThat(item(section(cashier, "cashUp"), "Variance")).isNull();
        assertThat(item(section(cashier, "cashUp"), "Cash count")).isEqualTo("Not counted");
        assertThat(cashier.checks()).noneMatch(check -> "agrees-with-cash-up".equals(check.key())
                && check.status() == CheckStatus.FAIL);
    }

    @Test
    void theCashierReading_namesTheTill() throws Exception {
        var shiftId = openShift(cashierA, t1, "500.00");

        assertThat(item(section(statements.cashierReading(shiftId), "header"), "Till")).isEqualTo(t1.label());
    }

    @Test
    void shiftsOnTheUnregisteredTill_areLabelledSo() throws Exception {
        var shiftId = openShift(cashierA, null, "500.00");

        var dayRow = row(rows(section(statements.dayReading(today(), null), "shifts")), "shiftId", shiftId);
        assertThat(dayRow.get("till")).isEqualTo("Unregistered till");
    }

    @Test
    void closeDayBlockers_nameTheTill() throws Exception {
        var shiftId = openShift(cashierA, t1, "500.00");

        var blocker = dayCloseRules.blockers(today()).stream().filter(b -> Long.valueOf(shiftId).equals(b.shiftId())
                && "SHIFT_OPEN".equals(b.code())).findFirst().orElseThrow();
        assertThat(blocker.message()).contains(", " + t1.code() + ")");
    }

    @Test
    void theAdminShiftViews_carryTheTill() throws Exception {
        var shiftId = openShift(cashierA, t1, "500.00");

        var list = body(mockMvc.perform(get("/admin/shifts").header("Authorization", "Bearer " + adminPwd)).andReturn());
        JsonNode listed = null;
        for (var node : list.get("content")) {
            if (node.get("shiftId").asLong() == shiftId) {
                listed = node;
            }
        }
        assertThat(listed).as("shift listed").isNotNull();
        assertThat(listed.get("tillCode").asText()).isEqualTo(t1.code());
        assertThat(listed.get("tillName").asText()).isEqualTo(t1.name());
        assertThat(listed.get("cashCount").asText()).isEqualTo("COUNTED");
        assertThat(listed.get("forceClosed").asBoolean()).isFalse();

        var activity = body(mockMvc.perform(get("/admin/shifts/" + shiftId + "/activity")
                .header("Authorization", "Bearer " + adminPwd)).andReturn());
        assertThat(activity.get("shift").get("tillCode").asText()).isEqualTo(t1.code());
    }

    @Test
    void newZReadings_useDefinitionVersion2() {
        assertThat(zReading.definitionVersion()).isEqualTo(2);
    }

    // ---- helpers ----

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ClockConfig.BUSINESS_ZONE);
    }

    private Till registerTill() throws Exception {
        var name = PREFIX + RANDOM.nextInt(1_000_000);
        var result = mockMvc.perform(post("/admin/devices").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", name, "serialNumber", "O2R" + Long.toString(Math.abs(RANDOM.nextLong()), 36).toUpperCase()))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("register till").isEqualTo(201);
        Cookie cookie = null;
        for (var header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith("deviceToken=")) {
                cookie = new Cookie("deviceToken", header.substring("deviceToken=".length(), header.indexOf(';')));
            }
        }
        return new Till(body(result).get("tillCode").asText(), name, cookie);
    }

    private MockHttpServletRequestBuilder on(MockHttpServletRequestBuilder request, String token, Till till) {
        request = request.header("Authorization", "Bearer " + token);
        return till == null ? request : request.cookie(till.cookie());
    }

    private long openShift(String token, Till till, String openingFloat) throws Exception {
        var result = mockMvc.perform(on(post("/shifts/open"), token, till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openingFloat", new BigDecimal(openingFloat))))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private long sell(String token, Till till) throws Exception {
        var request = Map.of(
                "payments", List.of(Map.of("method", "CASH", "amount", new BigDecimal("50.00"), "amountTendered", new BigDecimal("50.00"))),
                "lines", List.of(Map.of("dailyMealOptionId", optionId, "quantity", 1, "extras", List.of())));
        var result = mockMvc.perform(on(post("/orders"), token, till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(request))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private void refund(String token, Till till, long orderId) throws Exception {
        var request = Map.of("scope", "WHOLE_ORDER", "reasonCode", "CUSTOMER_COMPLAINT", "authorizationToken", pos.authorizePin(token));
        var result = mockMvc.perform(on(post("/orders/" + orderId + "/adjustments"), token, till)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(request))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
    }

    private static Map<String, Object> section(ReportResult result, String key) {
        return result.sections().stream().filter(s -> key.equals(s.get("key"))).findFirst()
                .orElseThrow(() -> new AssertionError("no section " + key));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> section) {
        return (List<Map<String, Object>>) section.get("rows");
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String key, Object value) {
        return rows.stream().filter(r -> value.equals(r.get(key))).findFirst()
                .orElseThrow(() -> new AssertionError("no row with " + key + "=" + value));
    }

    @SuppressWarnings("unchecked")
    private static Object item(Map<String, Object> section, String label) {
        return ((List<Map<String, Object>>) section.get("items")).stream().filter(i -> label.equals(i.get("label")))
                .findFirst().orElseThrow(() -> new AssertionError("no item " + label)).get("value");
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }
}
