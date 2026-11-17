package com.beaconculinary.api.orders;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.PosFlowHelper.ComplimentaryReq;
import com.beaconculinary.api.support.PosFlowHelper.DiscountReq;
import com.beaconculinary.api.support.PosFlowHelper.LineReq;
import com.beaconculinary.api.support.PosFlowHelper.PaymentReq;
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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O2b step 2: the VAT snapshot on new orders (VatRule on originalTotal, stored once) and the receipt
 * fields on OrderDto (cashierName, tillCode, tillName), in the 201, the O1 replay, GET /orders/{id}
 * and the reprint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O2bOrderReceiptIntegrationTests {
    private static final String PREFIX = "O2b Rcpt ";
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
    private String adminToken;
    private String cashierToken;
    private long optionId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var mealId = pos.createMealCatalog("O2b Meal", "50.00", pos.createComponent("O2b Beef", "15.00"));
        optionId = pos.createDailyOption(pos.periodId("lunch"), mealId, 100);
    }

    @AfterEach
    void tearDown() {
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
        jdbc.update("DELETE c FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id] WHERE d.[name] LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM [devices] WHERE [name] LIKE ?", PREFIX + "%");
        clock.reset();
    }

    // ---- the VAT snapshot ----

    @Test
    void aCashSale_storesTheRateAndTheVatIncluded() throws Exception {
        pos.openShift(cashierToken, "500.00");

        var order = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId)));

        assertThat(order.get("vatRate").decimalValue()).isEqualByComparingTo("0.15");
        assertThat(order.get("vatIncludedAmount").decimalValue()).isEqualByComparingTo("6.52");
        assertStored(order.get("id").asLong(), "0.1500", "6.52");
    }

    @Test
    void aDiscountedSale_takesVatOnTheDiscountedTotal() throws Exception {
        pos.openShift(cashierToken, "500.00");
        var discount = DiscountReq.fixed("10.00", pos.authorizePin(cashierToken));

        var order = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("40.00")), discount, new LineReq(optionId)));

        assertThat(order.get("originalTotal").decimalValue()).isEqualByComparingTo("40.00");
        // 40.00 × 0.15 ÷ 1.15 = 5.2173… → 5.22
        assertThat(order.get("vatIncludedAmount").decimalValue()).isEqualByComparingTo("5.22");
    }

    @Test
    void aSplitCashAndCardSale_takesVatOnTheWholeTotal() throws Exception {
        pos.openShift(cashierToken, "500.00");

        var order = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("30.00"), PaymentReq.card("20.00", "AUTH-1")),
                new LineReq(optionId)));

        assertThat(order.get("vatIncludedAmount").decimalValue()).isEqualByComparingTo("6.52");
    }

    @Test
    void aComplimentaryOrder_storesTheRate_andZeroVat() throws Exception {
        pos.openShift(cashierToken, "500.00");

        var order = body(pos.submitComplimentary(cashierToken, ComplimentaryReq.staffMeal(pos.authorizePin(cashierToken)),
                new LineReq(optionId)));

        assertThat(order.get("orderKind").asText()).isEqualTo("COMPLIMENTARY");
        assertThat(order.get("vatRate").decimalValue()).isEqualByComparingTo("0.15");
        assertThat(order.get("vatIncludedAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertStored(order.get("id").asLong(), "0.1500", "0.00");
    }

    @Test
    void aLaterRefund_leavesTheSnapshotUnchanged() throws Exception {
        pos.openShift(cashierToken, "500.00");
        var orderId = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        pos.markDone(orderId);

        pos.adjust(cashierToken, orderId, "WHOLE_ORDER");

        var order = body(mockMvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + cashierToken)));
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(order.get("originalTotal").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(order.get("vatIncludedAmount").decimalValue()).isEqualByComparingTo("6.52");
    }

    // ---- receipt fields, everywhere the order is returned ----

    @Test
    void theReceiptFields_areInThe201_theReplay_theGet_andTheReprint() throws Exception {
        var till = registerTill();
        mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + adminToken).cookie(till.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"openingFloat\":500.00}")).andReturn();
        var clientOrderId = UUID.randomUUID();
        var request = post("/orders").header("Authorization", "Bearer " + adminToken).cookie(till.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "clientOrderId", clientOrderId.toString(),
                        "payments", List.of(Map.of("method", "CASH", "amount", new BigDecimal("50.00"), "amountTendered", new BigDecimal("100.00"))),
                        "lines", List.of(Map.of("dailyMealOptionId", optionId, "quantity", 1, "extras", List.of())))));

        var created = mockMvc.perform(request).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        var orderId = body(created).get("id").asLong();
        var replay = mockMvc.perform(request).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        var fetched = mockMvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + adminToken)).andReturn();
        var reprint = mockMvc.perform(post("/admin/orders/" + orderId + "/reprint-receipt")
                .header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(reprint.getResponse().getStatus()).isEqualTo(200);

        for (var response : List.of(created, replay, fetched, reprint)) {
            var order = body(response);
            assertThat(order.get("cashierName").asText()).isEqualTo("Canteen Admin");
            assertThat(order.get("tillCode").asText()).isEqualTo(till.code());
            assertThat(order.get("tillName").asText()).isEqualTo(till.name());
            assertThat(order.get("vatRate").decimalValue()).isEqualByComparingTo("0.15");
            assertThat(order.get("vatIncludedAmount").decimalValue()).isEqualByComparingTo("6.52");
            assertThat(order.get("payments").get(0).get("changeDue").decimalValue()).isEqualByComparingTo("50.00");
        }
        assertThat(body(reprint).get("reprint").get("copyNumber").asInt()).isEqualTo(1);
    }

    @Test
    void onTheUnregisteredTill_theTillFieldsAreNull_andTheCashierIsNamed() throws Exception {
        pos.openShift(cashierToken, "500.00");

        var order = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId)));

        assertThat(order.get("tillCode").isNull()).isTrue();
        assertThat(order.get("tillName").isNull()).isTrue();
        assertThat(order.get("cashierName").asText()).isNotBlank();
    }

    @Test
    void anOrderFromBeforeO2b_reprintsWithNullVat() throws Exception {
        pos.openShift(cashierToken, "500.00");
        var orderId = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        jdbc.update("UPDATE [orders] SET [vat_rate] = NULL, [vat_included_amount] = NULL WHERE [id] = ?", orderId);

        var reprint = body(mockMvc.perform(post("/admin/orders/" + orderId + "/reprint-receipt")
                .header("Authorization", "Bearer " + adminToken)));

        assertThat(reprint.get("vatRate").isNull()).isTrue();
        assertThat(reprint.get("vatIncludedAmount").isNull()).isTrue();
        assertThat(reprint.get("originalTotal").decimalValue()).isEqualByComparingTo("50.00");
    }

    // ---- helpers ----

    record Till(String code, String name, Cookie cookie) {
    }

    private Till registerTill() throws Exception {
        var name = PREFIX + RANDOM.nextInt(1_000_000);
        var result = mockMvc.perform(post("/admin/devices").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", name, "serialNumber", "O2B" + Long.toString(Math.abs(RANDOM.nextLong()), 36).toUpperCase()))))
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

    private void assertStored(long orderId, String rate, String vat) {
        var row = jdbc.queryForMap("SELECT [vat_rate], [vat_included_amount] FROM [orders] WHERE [id] = ?", orderId);
        assertThat((BigDecimal) row.get("vat_rate")).isEqualTo(new BigDecimal(rate));
        assertThat((BigDecimal) row.get("vat_included_amount")).isEqualTo(new BigDecimal(vat));
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return body(result.andReturn());
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }
}
