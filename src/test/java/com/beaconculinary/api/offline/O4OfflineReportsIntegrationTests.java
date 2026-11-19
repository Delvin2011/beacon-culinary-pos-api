package com.beaconculinary.api.offline;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.reports.EndOfDayStatements;
import com.beaconculinary.api.reports.definitions.ZReadingReport;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.TestLocale;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.beaconculinary.api.support.O3Tills.body;
import static com.beaconculinary.api.support.O3Tills.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** O4: offline sales in the X, Z and cashier readings, late cash, and the cash reconciliation (contract section 9). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O4OfflineReportsIntegrationTests {
    private static final String PREFIX = "O4 Rep ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private OfflineProperties properties;
    @Autowired private EndOfDayStatements statements;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired private AuthorizationTokenRepository authorizationTokenRepository;
    @Autowired private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired private MealCatalogRepository mealCatalogRepository;
    @Autowired private ComponentCatalogRepository componentCatalogRepository;
    @Autowired private RecipeRepository recipeRepository;

    private O3Tills tills;
    private String adminPwd;
    private O3Tills.Cashier cashier;
    private O3Tills.Till t1;
    private String cashierToken;
    private long shiftId;
    private JsonNode grant;
    private long stewId;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 0));
        today = LocalDate.now(clock);
        properties.setEnabled(true);
        adminPwd = AuthTestHelper.loginAsAdmin(mockMvc);
        tills = new O3Tills(mockMvc, jdbc, PREFIX, adminPwd);
        cashier = tills.createCashier();
        t1 = tills.registerTill();
        var pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        stewId = pos.createDailyOption(pos.periodId("lunch"),
                pos.createMealCatalog("O4R Stew", "50.00", pos.createComponent("O4R Beef", "15.00")), 20);
        cashierToken = tills.pinLogin(cashier, t1);
        shiftId = tills.openShift(cashierToken, t1);
        var result = mockMvc.perform(on(post("/pos/offline/grant"), t1).header("Authorization", "Bearer " + cashierToken)).andReturn();
        grant = body(result);
        clock.setTime(LocalTime.of(12, 30));
    }

    @AfterEach
    void tearDown() {
        properties.setEnabled(false);
        jdbc.update("DELETE FROM [sale_exceptions]");
        jdbc.execute("DISABLE TRIGGER trg_z_readings_insert_only ON z_readings; DELETE FROM z_readings WHERE definition_version = 99; "
                + "ENABLE TRIGGER trg_z_readings_insert_only ON z_readings;");
        orderAdjustmentRepository.deleteAll();
        authorizationTokenRepository.deleteAll();
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        tills.cleanUp();
        clock.reset();
    }

    @Test
    void theXReading_showsOfflineSales_andWarnsOnOpenExceptions() throws Exception {
        var mismatch = sale(2, 21, "45.00");
        sync(List.of(sale(1, 20, "50.00"), mismatch));

        var x = report("x-reading", "tradingDay=" + today);

        assertThat(value(x, "offline", "Offline sales").asInt()).isEqualTo(2);
        assertThat(value(x, "offline", "Offline sales total").decimalValue()).isEqualByComparingTo("95.00");
        assertThat(value(x, "offline", "Open exceptions to review").asInt()).isEqualTo(1);
        assertThat(value(x, "offline", "Late offline cash after close").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(check(x, "offline-exceptions").get("status").asText()).isEqualTo("WARN");
    }

    @Test
    void lateCashAfterClose_showsOnItsOwnLine_andTheCashChecksStillAgree() throws Exception {
        var close = mockMvc.perform(on(post("/shifts/" + shiftId + "/close"), t1).header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":500.00}")).andReturn();
        assertThat(close.getResponse().getStatus()).as(close.getResponse().getContentAsString()).isEqualTo(200);

        sync(List.of(sale(1, 20, "50.00")));

        var reading = report("cashier-reading", "shiftId=" + shiftId);
        assertThat(value(reading, "cashUp", "Late offline cash after close").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(value(reading, "cashUp", "Expected cash").decimalValue()).isEqualByComparingTo("500.00");
        var x = report("x-reading", "tradingDay=" + today);
        assertThat(value(x, "offline", "Late offline cash after close").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(value(x, "offline", "Late offline cash sales").asInt()).isEqualTo(1);

        var cashUp = report("cash-up-variance", "from=" + today + "&to=" + today);
        assertThat(check(cashUp, "matches-tender").get("status").asText()).isNotEqualTo("FAIL");
        var tender = report("tender-summary", "from=" + today + "&to=" + today);
        assertThat(check(tender, "cash-matches-cashups").get("status").asText()).isNotEqualTo("FAIL");
    }

    @Test
    void aSaleBookedAfterAZ_countsOnTheNextDay() throws Exception {
        jdbc.update("""
                INSERT INTO [z_readings] ([z_number], [trading_day], [closed_by], [closed_at], [definition_version],
                    [gross_sales], [net_sales], [snapshot])
                SELECT COALESCE(MAX([z_number]), 0) + 1, ?, (SELECT [id] FROM [users] WHERE [email] = ?), SYSUTCDATETIME(), 99,
                    0, 0, '{}' FROM [z_readings]""", java.sql.Date.valueOf(today), AuthTestHelper.ADMIN_EMAIL);
        sync(List.of(sale(1, 20, "50.00")));

        clock.setTime(LocalTime.of(9, 0));
        clock.setInstant(today.plusDays(1).atTime(9, 0).atZone(TestLocale.ZONE).toInstant());
        assertThat(value(report("x-reading", "tradingDay=" + today.plusDays(1)), "offline", "Offline sales").asInt()).isEqualTo(1);
        assertThat(value(report("x-reading", "tradingDay=" + today), "offline", "Offline sales").asInt()).isZero();
    }

    @Test
    void theZ_isDefinition3_withTheOfflineSection() {
        assertThat(ZReadingReport.Z_DEFINITION_VERSION).isEqualTo(3);

        var z = statements.dayReading(today, new EndOfDayStatements.ZContext(1, "Canteen Admin", Instant.now()));

        assertThat(z.sections()).anyMatch(section -> "offline".equals(section.get("key")));
    }

    // ---- helpers ----

    private Map<String, Object> sale(int seq, int minuteAfterNoon, String price) {
        var amount = new BigDecimal(price);
        var sale = new HashMap<String, Object>();
        sale.put("clientOrderId", UUID.randomUUID().toString());
        sale.put("grantId", grant.get("grantId").asText());
        sale.put("offlineSeq", seq);
        sale.put("offlineReceiptNumber", "T1-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + String.format("-%04d", seq));
        sale.put("collectionNumber", "T1-" + seq);
        sale.put("soldAt", today.atTime(12, minuteAfterNoon).atZone(TestLocale.ZONE).toInstant().toString());
        sale.put("tillClockAtSendUtc", clock.instant().toString());
        sale.put("shiftId", shiftId);
        sale.put("cashierId", cashier.id());
        sale.put("lines", List.of(Map.of("dailyMealOptionId", stewId, "name", "O4R Stew", "station", "KITCHEN", "quantity", 1,
                "unitPrice", amount, "lineTotal", amount, "extras", List.of())));
        sale.put("subtotal", amount);
        sale.put("total", amount);
        sale.put("vatRate", new BigDecimal("0.1500"));
        sale.put("vatIncludedAmount", amount.multiply(new BigDecimal("0.15")).divide(new BigDecimal("1.15"), 2, java.math.RoundingMode.HALF_UP));
        sale.put("payments", List.of(Map.of("method", "CASH", "amount", amount, "amountTendered", amount, "changeDue", BigDecimal.ZERO)));
        return sale;
    }

    private void sync(List<Map<String, Object>> sales) throws Exception {
        var result = mockMvc.perform(on(post("/pos/offline/sales"), t1).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("grants", List.of(grant.get("grant").asText()), "sales", sales)))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        for (var item : body(result).get("results")) {
            assertThat(item.get("result").asText()).startsWith("CREATED");
        }
    }

    private JsonNode report(String key, String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/reports/" + key + "?" + query).header("Authorization", "Bearer " + adminPwd))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    private static JsonNode value(JsonNode report, String section, String label) {
        for (var s : report.get("sections")) {
            if (section.equals(s.get("key").asText())) {
                for (var item : s.get("items")) {
                    if (label.equals(item.get("label").asText())) {
                        return item.get("value");
                    }
                }
            }
        }
        throw new AssertionError(section + " / " + label + " not found");
    }

    private static JsonNode check(JsonNode report, String key) {
        for (var check : report.get("checks")) {
            if (key.equals(check.get("key").asText())) {
                return check;
            }
        }
        throw new AssertionError("check " + key + " not found in " + report.get("checks"));
    }
}
