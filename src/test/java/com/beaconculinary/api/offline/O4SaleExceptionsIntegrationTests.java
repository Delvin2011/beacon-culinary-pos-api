package com.beaconculinary.api.offline;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.dashboard.DashboardCache;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.offline.exceptions.SaleExceptionCode;
import com.beaconculinary.api.offline.exceptions.SaleExceptionStore;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.PosFlowHelper.LineReq;
import com.beaconculinary.api.support.PosFlowHelper.PaymentReq;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.beaconculinary.api.support.O3Tills.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** O4: exception review (contract section 8), and the counts in GET /admin/tills and the dashboard. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O4SaleExceptionsIntegrationTests {
    private static final String PREFIX = "O4 Exc ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private SaleExceptionStore exceptions;
    @Autowired private DashboardCache dashboardCache;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired private AuthorizationTokenRepository authorizationTokenRepository;
    @Autowired private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired private MealCatalogRepository mealCatalogRepository;
    @Autowired private ComponentCatalogRepository componentCatalogRepository;
    @Autowired private RecipeRepository recipeRepository;

    private String adminToken;
    private String cashierToken;
    private O3Tills tills;
    private O3Tills.Till till;
    private long orderId;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        today = LocalDate.now(clock);
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        tills = new O3Tills(mockMvc, jdbc, PREFIX, adminToken);
        till = tills.registerTill();
        var pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var option = pos.createDailyOption(pos.periodId("lunch"),
                pos.createMealCatalog("O4E Stew", "50.00", pos.createComponent("O4E Beef", "15.00")), 20);
        pos.openShift(cashierToken, "500.00");
        orderId = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("50.00")), new LineReq(option)).andReturn())
                .get("id").asLong();
        dashboardCache.clear();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM [sale_exceptions]");
        jdbc.update("DELETE FROM [receipt_reprints]");
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
        tills.cleanUp();
        dashboardCache.clear();
        clock.reset();
    }

    // ---- list and detail ----

    @Test
    void theList_showsOpenReviewExceptions_byDefault_andFilters() throws Exception {
        var device = UUID.fromString(till.id());
        var oversold = add(SaleExceptionCode.OVERSOLD, "{\"shortfall\":1}", device, today);
        add(SaleExceptionCode.PERIOD_CLOSED, "{}", device, today);
        add(SaleExceptionCode.PRICE_MISMATCH, "{}", null, today.minusDays(1));

        var page = body(list(""));
        assertThat(page.get("totalItems").asLong()).isEqualTo(2);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(50);
        assertThat(codes(page)).containsExactlyInAnyOrder("OVERSOLD", "PRICE_MISMATCH");
        var item = find(page, oversold);
        assertThat(item.get("severity").asText()).isEqualTo("REVIEW");
        assertThat(item.get("status").asText()).isEqualTo("OPEN");
        assertThat(item.get("tradingDay").asText()).isEqualTo(today.toString());
        assertThat(item.get("deviceId").asText()).isEqualTo(till.id());
        assertThat(item.get("detail").get("shortfall").asInt()).isEqualTo(1);
        assertThat(item.get("order").get("id").asLong()).isEqualTo(orderId);
        assertThat(item.get("order").get("total").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(item.get("order").get("receiptNumber").asText()).isNotBlank();
        assertThat(item.get("createdAt").asText()).endsWith("Z");

        assertThat(codes(body(list("?severity=INFO")))).containsExactly("PERIOD_CLOSED");
        assertThat(codes(body(list("?code=PRICE_MISMATCH")))).containsExactly("PRICE_MISMATCH");
        assertThat(codes(body(list("?tradingDay=" + today)))).containsExactly("OVERSOLD");
        assertThat(codes(body(list("?deviceId=" + till.id())))).containsExactly("OVERSOLD");
        assertThat(body(list("?size=1")).get("items")).hasSize(1);
        assertThat(body(list("?size=1&page=1")).get("items")).hasSize(1);
        assertThat(body(list("?status=RESOLVED")).get("totalItems").asLong()).isZero();
        assertThat(list("?code=NOPE").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void theDetail_carriesTheFullOrder() throws Exception {
        var id = add(SaleExceptionCode.UNKNOWN_ITEM, "{\"name\":\"Mystery\"}", null, today);

        var detail = body(as(adminToken, get("/admin/sale-exceptions/" + id)));

        assertThat(detail.get("code").asText()).isEqualTo("UNKNOWN_ITEM");
        assertThat(detail.get("detail").get("name").asText()).isEqualTo("Mystery");
        assertThat(detail.get("order").get("lines")).hasSize(1);
        assertThat(detail.get("resolution").isNull()).isTrue();
        assertThat(as(adminToken, get("/admin/sale-exceptions/999999999")).getResponse().getStatus()).isEqualTo(404);
    }

    // ---- resolve ----

    @Test
    void resolve_needsANote_recordsWhoAndWhen_andHappensOnce() throws Exception {
        var id = add(SaleExceptionCode.OVERSOLD, "{}", null, today);

        assertThat(body(resolve(id, "   ")).get("note").asText()).isNotBlank();
        assertThat(resolve(id, "n".repeat(501)).getResponse().getStatus()).isEqualTo(400);
        assertThat(as(adminToken, post("/admin/sale-exceptions/" + id + "/resolve")).getResponse().getStatus()).isEqualTo(400);

        var resolved = body(resolve(id, " Counted; spare tray. "));
        assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolution").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolutionNote").asText()).isEqualTo("Counted; spare tray.");
        assertThat(resolved.get("resolvedBy").get("name").asText()).isEqualTo("Canteen Admin");
        assertThat(resolved.get("resolvedAt").asText()).endsWith("Z");

        assertError(resolve(id, "again"), 409, "EXCEPTION_ALREADY_RESOLVED");
        assertThat(body(list("")).get("totalItems").asLong()).isZero();
    }

    @Test
    void anAccountApproval_cantBeResolved_itsApproved_recordingTheApprover() throws Exception {
        jdbc.update("""
                INSERT INTO [accounts] ([name], [department], [contact_number], [contact_email], [active])
                VALUES (?, 'Finance', '021 555 0103', 'o4exc@example.test', 1)""", PREFIX + "Finance");
        var accountId = jdbc.queryForObject("SELECT [id] FROM [accounts] WHERE [name] = ?", Long.class, PREFIX + "Finance");
        jdbc.update("UPDATE [order_payments] SET [method] = 'ACCOUNT', [account_id] = ?, [amount_tendered] = NULL, "
                + "[change_due] = NULL, [authorized_by] = NULL WHERE [order_id] = ?", accountId, orderId);
        var id = add(SaleExceptionCode.ACCOUNT_APPROVAL_PENDING, "{\"accountId\":" + accountId + ",\"amount\":50.00}", null, today);
        var other = add(SaleExceptionCode.OVERSOLD, "{}", null, today);

        assertError(resolve(id, "ok"), 409, "USE_APPROVE_ACCOUNT");
        assertError(approve(other, null), 409, "NOT_AN_ACCOUNT_APPROVAL");

        var approved = body(approve(id, "Confirmed with Finance"));
        assertThat(approved.get("resolution").asText()).isEqualTo("ACCOUNT_APPROVED");
        assertThat(approved.get("resolutionNote").asText()).isEqualTo("Confirmed with Finance");
        assertThat(jdbc.queryForObject("SELECT [authorized_by] FROM [order_payments] WHERE [order_id] = ?", Long.class, orderId))
                .isEqualTo(AuthTestHelper.adminId(mockMvc));
        assertThat(approved.get("order").get("payments").get(0).get("authorizedBy").get("name").asText()).isEqualTo("Canteen Admin");
        assertError(approve(id, null), 409, "EXCEPTION_ALREADY_RESOLVED");
        jdbc.update("DELETE FROM [order_payments] WHERE [order_id] = ?", orderId);
        jdbc.update("DELETE FROM [accounts] WHERE [name] LIKE ?", PREFIX + "%");
    }

    @Test
    void onlyAnAdminReviews() throws Exception {
        var id = add(SaleExceptionCode.OVERSOLD, "{}", null, today);

        assertThat(as(cashierToken, get("/admin/sale-exceptions")).getResponse().getStatus()).isEqualTo(403);
        assertThat(resolveAs(cashierToken, id, "x").getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/admin/sale-exceptions")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ---- counts ----

    @Test
    void tillsAndTheDashboard_countOpenReviewExceptions() throws Exception {
        var device = UUID.fromString(till.id());
        add(SaleExceptionCode.OVERSOLD, "{}", device, today);
        add(SaleExceptionCode.PRICE_MISMATCH, "{}", device, today);
        add(SaleExceptionCode.PERIOD_CLOSED, "{}", device, today);
        var resolved = add(SaleExceptionCode.CLOCK_SKEW, "{}", device, today);
        resolve(resolved, "fine");

        JsonNode row = null;
        for (var node : body(as(adminToken, get("/admin/tills")))) {
            if (till.id().equals(node.get("deviceId").asText())) {
                row = node;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("offline").get("openExceptions").asInt()).isEqualTo(2);

        dashboardCache.clear();
        var attention = body(as(adminToken, get("/admin/dashboard/sections/attention")));
        JsonNode item = null;
        for (var node : attention.get("data").get("items")) {
            if ("OFFLINE_EXCEPTIONS".equals(node.get("key").asText())) {
                item = node;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("count").asInt()).isEqualTo(2);
        assertThat(item.get("action").get("path").asText()).isEqualTo("/sale-exceptions");
    }

    // ---- helpers ----

    private long add(SaleExceptionCode code, String detail, UUID device, LocalDate day) {
        return exceptions.insert(orderId, code, detail, device, day);
    }

    private MvcResult list(String query) throws Exception {
        return as(adminToken, get("/admin/sale-exceptions" + query));
    }

    private MvcResult resolve(long id, String note) throws Exception {
        return resolveAs(adminToken, id, note);
    }

    private MvcResult resolveAs(String token, long id, String note) throws Exception {
        return mockMvc.perform(post("/admin/sale-exceptions/" + id + "/resolve").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of("note", note)))).andReturn();
    }

    private MvcResult approve(long id, String note) throws Exception {
        var request = post("/admin/sale-exceptions/" + id + "/approve-account").header("Authorization", "Bearer " + adminToken);
        if (note != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of("note", note)));
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token)).andReturn();
    }

    private static List<String> codes(JsonNode page) {
        var codes = new ArrayList<String>();
        page.get("items").forEach(item -> codes.add(item.get("code").asText()));
        return codes;
    }

    private static JsonNode find(JsonNode page, long id) {
        for (var item : page.get("items")) {
            if (item.get("id").asLong() == id) {
                return item;
            }
        }
        throw new AssertionError("exception " + id + " not listed");
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(body(result).get("code").asText()).isEqualTo(code);
    }
}
