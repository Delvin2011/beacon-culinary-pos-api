package com.beaconculinary.api.shifts;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.TestStaffCleanup;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O2 step 4 (R3, R5, R6, R14): shifts, sales and adjustments per till. The till comes only from the
 * deviceToken cookie; requests without one use the shared unregistered till, as before O2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O2PerTillShiftIntegrationTests {
    static final String PREFIX = "O2 Till ";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ShiftRepository shiftRepository;
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
    private final List<Long> staff = new ArrayList<>();
    private String adminToken;
    private String cashierA;
    private String cashierB;
    private String cashierC;
    private long optionId;
    private Till t1;
    private Till t2;
    private Till t3;

    record Till(String deviceId, String code, Cookie cookie) {
    }

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierA = AuthTestHelper.loginAsCashier(mockMvc);
        cashierB = AuthTestHelper.loginWithPin(mockMvc, AuthTestHelper.cashierBId(mockMvc), "654321");
        cashierC = newCashierToken();
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var mealId = pos.createMealCatalog("O2 Meal", "50.00", pos.createComponent("O2 Beef", "15.00"));
        optionId = pos.createDailyOption(pos.periodId("lunch"), mealId, 500);
        t1 = registerTill();
        t2 = registerTill();
        t3 = registerTill();
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
        shiftRepository.deleteAll();
        jdbc.update("DELETE c FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id] WHERE d.[name] LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM [devices] WHERE [name] LIKE ?", PREFIX + "%");
        TestStaffCleanup.delete(jdbc, staff);
        staff.clear();
        clock.reset();
    }

    // ---- three tills at once ----

    @Test
    void threeTills_threeCashiers_eachSellOnTheirOwnShift_atTheSameTime() throws Exception {
        var s1 = shiftId(open(cashierA, t1, "500.00"));
        var s2 = shiftId(open(cashierB, t2, "300.00"));
        var s3 = shiftId(open(cashierC, t3, "100.00"));

        var start = new CountDownLatch(1);
        var calls = new ArrayList<Callable<Integer>>();
        for (int i = 0; i < 4; i++) {
            calls.add(() -> { start.await(); return sell(cashierA, t1).getResponse().getStatus(); });
            calls.add(() -> { start.await(); return sell(cashierB, t2).getResponse().getStatus(); });
            calls.add(() -> { start.await(); return sell(cashierC, t3).getResponse().getStatus(); });
        }
        assertThat(runConcurrently(start, calls)).containsOnly(201).hasSize(12);

        for (var expected : List.of(new Object[]{s1, t1, "700.00"}, new Object[]{s2, t2, "500.00"}, new Object[]{s3, t3, "300.00"})) {
            var shiftId = (long) expected[0];
            var till = (Till) expected[1];
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE shift_id = ? AND device_id = ?",
                    Integer.class, shiftId, till.deviceId())).as("orders on " + till.code()).isEqualTo(4);
            assertThat(expectedCash(shiftId)).as("expected cash on " + till.code()).isEqualByComparingTo((String) expected[2]);
        }
    }

    // ---- opening ----

    @Test
    void openShift_recordsTheTill() throws Exception {
        var result = open(cashierA, t1, "500.00");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        var shift = body(result);
        assertThat(shift.get("deviceId").asText()).isEqualToIgnoringCase(t1.deviceId());
        assertThat(shift.get("tillCode").asText()).isEqualTo(t1.code());
        assertThat(shift.get("tillName").asText()).startsWith(PREFIX);
        assertThat(shift.get("forceClosed").asBoolean()).isFalse();
        assertThat(shift.get("cashCount").asText()).isEqualTo("COUNTED");
    }

    @Test
    void secondShiftOnTheSameTill_isRefused_withTheHolder() throws Exception {
        var s1 = shiftId(open(cashierA, t1, "500.00"));

        var result = open(cashierB, t1, "300.00");

        assertError(result, 409, "SHIFT_ALREADY_OPEN");
        var details = body(result).get("details");
        assertThat(details.get("shiftId").asLong()).isEqualTo(s1);
        assertThat(details.get("ownerName").asText()).isNotBlank();
        assertThat(details.get("tillCode").asText()).isEqualTo(t1.code());
    }

    @Test
    void ownShiftOnThisTill_openingAgain_isShiftAlreadyOpen() throws Exception {
        open(cashierA, t1, "500.00");

        assertError(open(cashierA, t1, "500.00"), 409, "SHIFT_ALREADY_OPEN");
    }

    @Test
    void cashierWithAShiftOnAnotherTill_isRefused_withThatTill() throws Exception {
        open(cashierA, t1, "500.00");

        var result = open(cashierA, t2, "300.00");

        assertError(result, 409, "SHIFT_OPEN_ON_OTHER_TILL");
        assertThat(body(result).get("details").get("tillCode").asText()).isEqualTo(t1.code());
        assertThat(body(result).get("details").get("tillName").asText()).startsWith(PREFIX);
    }

    @Test
    void adminIsNotExempt_fromOneOpenShiftPerCashier() throws Exception {
        open(adminToken, t1, "500.00");

        assertError(open(adminToken, t2, "300.00"), 409, "SHIFT_OPEN_ON_OTHER_TILL");
    }

    // ---- the unregistered till keeps today's behaviour ----

    @Test
    void unregisteredBrowsers_shareOneShift_asBefore() throws Exception {
        var shift = shiftId(open(cashierA, null, "500.00"));
        assertThat(body(getOn(cashierA, null, "/shifts/current")).get("tillName").asText()).isEqualTo("Unregistered till");

        var second = open(cashierB, null, "300.00");
        assertError(second, 409, "SHIFT_ALREADY_OPEN");
        assertThat(body(second).get("details").get("tillCode").isNull()).isTrue();

        assertError(sell(cashierB, null), 409, "TILL_IN_USE");
        var adminSale = sell(adminToken, null);
        assertThat(adminSale.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(adminSale).get("shiftId").asLong()).isEqualTo(shift);
        assertThat(body(adminSale).get("tillCode").isNull()).isTrue();
    }

    @Test
    void aShiftOnTheUnregisteredTill_blocksTheSameCashierOnARegisteredTill() throws Exception {
        open(cashierA, null, "500.00");

        var result = open(cashierA, t1, "300.00");

        assertError(result, 409, "SHIFT_OPEN_ON_OTHER_TILL");
        assertThat(body(result).get("details").get("tillCode").isNull()).isTrue();
        assertThat(body(result).get("details").get("tillName").asText()).isEqualTo("Unregistered till");
    }

    @Test
    void unregisteredTill_andRegisteredTills_tradeSideBySide() throws Exception {
        var nullShift = shiftId(open(cashierA, null, "500.00"));
        var s1 = shiftId(open(cashierB, t1, "300.00"));

        assertThat(body(sell(cashierA, null)).get("shiftId").asLong()).isEqualTo(nullShift);
        assertThat(body(sell(cashierB, t1)).get("shiftId").asLong()).isEqualTo(s1);
    }

    // ---- selling ----

    @Test
    void sale_onATillWithoutAShift_isNoOpenShift_evenWhenAnotherTillHasOne() throws Exception {
        open(cashierA, t1, "500.00");

        assertError(sell(cashierA, t2), 409, "NO_OPEN_SHIFT");
        assertError(sell(cashierA, null), 409, "NO_OPEN_SHIFT");
    }

    @Test
    void sale_recordsTheTill_andTheResponseCarriesItsCode() throws Exception {
        var s1 = shiftId(open(cashierA, t1, "500.00"));

        var sale = sell(cashierA, t1);

        assertThat(sale.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(sale).get("shiftId").asLong()).isEqualTo(s1);
        assertThat(body(sale).get("tillCode").asText()).isEqualTo(t1.code());
        var orderId = body(sale).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT device_id FROM orders WHERE id = ?", String.class, orderId))
                .isEqualToIgnoringCase(t1.deviceId());
    }

    @Test
    void anotherCashier_onTheTill_isTillInUse_andAnAdminMaySell() throws Exception {
        open(cashierA, t1, "500.00");

        assertError(sell(cashierB, t1), 409, "TILL_IN_USE");
        assertThat(sell(adminToken, t1).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void getOpenShift_returnsThisTillsShift() throws Exception {
        var s1 = shiftId(open(cashierA, t1, "500.00"));

        var onT1 = getOn(cashierB, t1, "/shifts/open");
        assertThat(onT1.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(onT1).get("shiftId").asLong()).isEqualTo(s1);
        assertThat(body(onT1).get("tillCode").asText()).isEqualTo(t1.code());
        assertThat(body(onT1).get("ownedByCaller").asBoolean()).isFalse();

        assertThat(getOn(cashierB, t2, "/shifts/open").getResponse().getStatus()).isEqualTo(404);
        assertThat(getOn(cashierB, null, "/shifts/open").getResponse().getStatus()).isEqualTo(404);
    }

    // ---- adjustments ----

    @Test
    void adjustment_byAnotherCashierOnTheTill_isTillInUse_andAnAdminMayAdjust() throws Exception {
        open(cashierA, t1, "500.00");
        var orderId = body(sell(cashierA, t1)).get("id").asLong();

        assertError(adjust(cashierB, t1, orderId), 409, "TILL_IN_USE");
        assertThat(adjust(adminToken, t1, orderId).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void cashRefund_onAnotherTill_booksToThatTillsShift() throws Exception {
        var s1 = shiftId(open(cashierA, t1, "500.00"));
        var s2 = shiftId(open(cashierB, t2, "300.00"));
        var orderId = body(sell(cashierA, t1)).get("id").asLong();
        pos.markDone(orderId);

        var refund = adjust(cashierB, t2, orderId);

        assertThat(refund.getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT shift_id FROM order_adjustments WHERE order_id = ?", Long.class, orderId))
                .isEqualTo(s2);
        // The cash left T2's drawer; T1's sale stays in T1's figures.
        assertThat(expectedCash(s1)).isEqualByComparingTo("550.00");
        assertThat(expectedCash(s2)).isEqualByComparingTo("250.00");
    }

    // ---- closing ----

    @Test
    void closingAClosedShift_isShiftAlreadyClosed() throws Exception {
        var s1 = shiftId(open(cashierA, t1, "500.00"));
        assertThat(pos.closeShift(cashierA, s1, "500.00").andReturn().getResponse().getStatus()).isEqualTo(200);

        assertError(pos.closeShift(cashierA, s1, "500.00").andReturn(), 409, "SHIFT_ALREADY_CLOSED");
    }

    @Test
    void afterClosing_anotherCashierCanOpenOnThatTill() throws Exception {
        var s1 = shiftId(open(cashierA, t1, "500.00"));
        pos.closeShift(cashierA, s1, "500.00");

        assertThat(open(cashierB, t1, "300.00").getResponse().getStatus()).isEqualTo(201);
    }

    // ---- opening at the same moment ----

    @Test
    void twoCashiers_openingOnOneTill_atOnce_oneWins() throws Exception {
        var start = new CountDownLatch(1);
        List<Callable<MvcResult>> calls = List.of(
                () -> { start.await(); return open(cashierA, t1, "500.00"); },
                () -> { start.await(); return open(cashierB, t1, "300.00"); });

        var results = runConcurrentlyResults(start, calls);

        assertThat(results.stream().map(r -> r.getResponse().getStatus())).containsExactlyInAnyOrder(201, 409);
        var refused = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat(body(refused).get("code").asText()).isEqualTo("SHIFT_ALREADY_OPEN");
    }

    @Test
    void oneCashier_openingOnTwoTills_atOnce_oneWins() throws Exception {
        var start = new CountDownLatch(1);
        List<Callable<MvcResult>> calls = List.of(
                () -> { start.await(); return open(cashierA, t1, "500.00"); },
                () -> { start.await(); return open(cashierA, t2, "300.00"); });

        var results = runConcurrentlyResults(start, calls);

        assertThat(results.stream().map(r -> r.getResponse().getStatus())).containsExactlyInAnyOrder(201, 409);
        var refused = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat(body(refused).get("code").asText()).isEqualTo("SHIFT_OPEN_ON_OTHER_TILL");
    }

    // ---- helpers ----

    private Till registerTill() throws Exception {
        var name = PREFIX + RANDOM.nextInt(1_000_000);
        var serial = "O2T" + Long.toString(Math.abs(RANDOM.nextLong()), 36).toUpperCase();
        var result = mockMvc.perform(post("/admin/devices").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of("name", name, "serialNumber", serial))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("register till").isEqualTo(201);
        Cookie cookie = null;
        for (var header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith("deviceToken=")) {
                cookie = new Cookie("deviceToken", header.substring("deviceToken=".length(), header.indexOf(';')));
            }
        }
        assertThat(cookie).as("till cookie").isNotNull();
        return new Till(body(result).get("id").asText(), body(result).get("tillCode").asText(), cookie);
    }

    private String newCashierToken() throws Exception {
        for (int tries = 0; tries < 5; tries++) {
            var pin = String.format("%06d", RANDOM.nextInt(1_000_000));
            var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(Map.of("name", PREFIX + "Cashier " + RANDOM.nextInt(1_000_000),
                            "role", "CASHIER", "pin", pin)))).andReturn();
            if (result.getResponse().getStatus() == 201) {
                var id = body(result).get("id").asLong();
                staff.add(id);
                return AuthTestHelper.loginWithPin(mockMvc, id, pin);
            }
        }
        throw new IllegalStateException("Could not create a cashier with a unique PIN");
    }

    private MockHttpServletRequestBuilder onTill(MockHttpServletRequestBuilder request, String token, Till till) {
        request = request.header("Authorization", "Bearer " + token);
        return till == null ? request : request.cookie(till.cookie());
    }

    private MvcResult open(String token, Till till, String openingFloat) throws Exception {
        return mockMvc.perform(onTill(post("/shifts/open"), token, till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openingFloat", new BigDecimal(openingFloat))))).andReturn();
    }

    private MvcResult sell(String token, Till till) throws Exception {
        var body = Map.of(
                "payments", List.of(Map.of("method", "CASH", "amount", new BigDecimal("50.00"), "amountTendered", new BigDecimal("100.00"))),
                "lines", List.of(Map.of("dailyMealOptionId", optionId, "quantity", 1, "extras", List.of())));
        return mockMvc.perform(onTill(post("/orders"), token, till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body))).andReturn();
    }

    /** A whole-order void or refund (by the order's status), approved with the manager PIN. */
    private MvcResult adjust(String token, Till till, long orderId) throws Exception {
        var body = Map.of("scope", "WHOLE_ORDER", "reasonCode", "CUSTOMER_COMPLAINT",
                "authorizationToken", pos.authorizePin(token));
        return mockMvc.perform(onTill(post("/orders/" + orderId + "/adjustments"), token, till)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body))).andReturn();
    }

    private MvcResult getOn(String token, Till till, String path) throws Exception {
        return mockMvc.perform(onTill(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path), token, till))
                .andReturn();
    }

    private BigDecimal expectedCash(long shiftId) throws Exception {
        return body(mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + adminToken))
                .andReturn()).get("expectedCash").decimalValue();
    }

    private static long shiftId(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as("open shift: " + result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as("status: " + result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(body(result).get("code").asText()).as("code").isEqualTo(code);
    }

    private static List<Integer> runConcurrently(CountDownLatch start, List<Callable<Integer>> calls) throws Exception {
        var pool = Executors.newFixedThreadPool(calls.size());
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (var call : calls) {
                futures.add(pool.submit(call));
            }
            start.countDown();
            var results = new ArrayList<Integer>();
            for (var future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<MvcResult> runConcurrentlyResults(CountDownLatch start, List<Callable<MvcResult>> calls) throws Exception {
        var pool = Executors.newFixedThreadPool(calls.size());
        try {
            var futures = new ArrayList<java.util.concurrent.Future<MvcResult>>();
            for (var call : calls) {
                futures.add(pool.submit(call));
            }
            start.countDown();
            var results = new ArrayList<MvcResult>();
            for (var future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
