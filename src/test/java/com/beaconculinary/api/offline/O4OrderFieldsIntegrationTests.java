package com.beaconculinary.api.offline;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
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
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.PosFlowHelper.ExtraReq;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O4 step 2: the offline fields on OrderDto and reprints (contract section 7), lines without a menu
 * link, the report views' booked day and sale hour (V111), and the exception store.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O4OrderFieldsIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private SaleExceptionStore exceptions;
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
    private long stockId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var component = pos.createComponent("O4 Beef", "15.00");
        var lunch = pos.periodId("lunch");
        optionId = pos.createDailyOption(lunch, pos.createMealCatalog("O4 Stew", "50.00", component), 100);
        stockId = pos.createDailyComponentStock(lunch, component, 20);
        pos.openShift(cashierToken, "500.00");
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
        clock.reset();
    }

    // ---- OrderDto ----

    @Test
    void anOnlineSale_hasNoOfflineFields() throws Exception {
        var order = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId)));

        assertThat(order.get("recordedOffline").asBoolean()).isFalse();
        assertThat(order.get("soldAt").isNull()).isTrue();
        assertThat(order.get("collectionNumber").isNull()).isTrue();
        assertThat(order.get("bookedTradingDay").isNull()).isTrue();
        assertThat(order.get("receiptNumber").asText()).matches("\\d{8}-\\d{4,}");
    }

    @Test
    void anOfflineSale_showsTheTillsNumbers_everywhere_includingReprints() throws Exception {
        var orderId = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId))).get("id").asLong();
        jdbc.update("""
                UPDATE [orders] SET [recorded_offline] = 1, [offline_receipt_number] = 'T1-20261118-0007',
                    [collection_number] = 'T1-7', [sold_at] = ?, [booked_trading_day] = ? WHERE [id] = ?""",
                LocalDateTime.of(2026, 11, 18, 8, 42, 15), LocalDate.of(2026, 11, 19), orderId);

        var fetched = body(mockMvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + cashierToken)));
        var reprint = body(mockMvc.perform(post("/admin/orders/" + orderId + "/reprint-receipt")
                .header("Authorization", "Bearer " + adminToken)));

        for (var order : List.of(fetched, reprint)) {
            assertThat(order.get("recordedOffline").asBoolean()).isTrue();
            assertThat(order.get("receiptNumber").asText()).isEqualTo("T1-20261118-0007");
            assertThat(order.get("collectionNumber").asText()).isEqualTo("T1-7");
            assertThat(order.get("soldAt").asText()).isEqualTo("2026-11-18T08:42:15Z");
            assertThat(order.get("bookedTradingDay").asText()).isEqualTo("2026-11-19");
        }
    }

    // ---- lines without a menu link ----

    @Test
    void aLineWithoutAMenuLink_showsItsOwnName_andCanBeRefunded() throws Exception {
        var order = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("65.00")),
                new LineReq(optionId, 1, List.of(new ExtraReq(stockId, 1)))));
        var orderId = order.get("id").asLong();
        var lineId = order.get("lines").get(0).get("id").asLong();
        jdbc.update("UPDATE [order_lines] SET [daily_meal_option_id] = NULL, [name] = 'Mystery stew' WHERE [id] = ?", lineId);
        jdbc.update("UPDATE [order_line_extras] SET [daily_component_stock_id] = NULL, [component_name] = 'Mystery extra' "
                + "WHERE [order_line_id] = ?", lineId);

        var fetched = body(mockMvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + cashierToken)));
        assertThat(fetched.get("lines").get(0).get("name").asText()).isEqualTo("Mystery stew");
        assertThat(fetched.get("lines").get(0).get("dailyMealOptionId").isNull()).isTrue();
        assertThat(fetched.get("lines").get(0).get("extras").get(0).get("componentName").asText()).isEqualTo("Mystery extra");

        var facts = jdbc.queryForList("SELECT [item_type], [item_name], [catalog_id] FROM [v_order_line_facts] WHERE [order_id] = ?",
                orderId);
        assertThat(facts).extracting(row -> row.get("item_name")).containsExactlyInAnyOrder("Mystery stew", "Mystery extra");
        assertThat(facts).allMatch(row -> row.get("catalog_id") == null);

        pos.markDone(orderId);
        pos.adjust(cashierToken, orderId, "WHOLE_ORDER");
        assertThat(jdbc.queryForObject("SELECT [status] FROM [orders] WHERE [id] = ?", String.class, orderId)).isEqualTo("REFUNDED");
    }

    // ---- report views ----

    @Test
    void reports_countABookedSaleOnItsBookedDay_atItsSaleHour() throws Exception {
        var orderId = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId))).get("id").asLong();
        var shiftDay = jdbc.queryForObject("SELECT [day_of_sale] FROM [v_order_facts] WHERE [order_id] = ?", LocalDate.class, orderId);
        assertThat(shiftDay).isEqualTo(LocalDate.now(clock));

        var booked = shiftDay.plusDays(1);
        // 06:05 UTC = 08:05 in Johannesburg.
        jdbc.update("UPDATE [orders] SET [booked_trading_day] = ?, [sold_at] = ? WHERE [id] = ?",
                booked, shiftDay.atTime(6, 5), orderId);

        var row = jdbc.queryForMap("SELECT [day_of_sale], [sale_hour] FROM [v_order_facts] WHERE [order_id] = ?", orderId);
        assertThat(row.get("day_of_sale").toString()).isEqualTo(booked.toString());
        assertThat(((Number) row.get("sale_hour")).intValue()).isEqualTo(8);
        assertThat(jdbc.queryForList("SELECT DISTINCT [trading_day] FROM [v_order_line_facts] WHERE [order_id] = ?",
                LocalDate.class, orderId)).containsExactly(booked);
    }

    // ---- exception store ----

    @Test
    void exceptions_areStored_listed_filtered_andResolvedOnce() throws Exception {
        var orderId = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId))).get("id").asLong();
        var day = LocalDate.now(clock);
        var device = java.util.UUID.randomUUID();
        var oversold = exceptions.insert(orderId, SaleExceptionCode.OVERSOLD, "{\"shortfall\":1}", device, day);
        exceptions.insert(orderId, SaleExceptionCode.PERIOD_CLOSED, "{}", device, day);

        var row = exceptions.find(oversold).orElseThrow();
        assertThat(row.open()).isTrue();
        assertThat(row.severity()).isEqualTo(SaleExceptionCode.Severity.REVIEW);
        assertThat(row.deviceId()).isEqualTo(device);
        assertThat(exceptions.forOrder(orderId)).hasSize(2);
        assertThat(exceptions.exists(orderId, SaleExceptionCode.PERIOD_CLOSED)).isTrue();

        var review = new SaleExceptionStore.Filter("OPEN", SaleExceptionCode.Severity.REVIEW, null, day, null);
        assertThat(exceptions.count(review)).isEqualTo(1);
        assertThat(exceptions.list(review, 0, 50)).extracting(SaleExceptionStore.ExceptionRow::code)
                .containsExactly(SaleExceptionCode.OVERSOLD);
        assertThat(exceptions.openReviewByDevice().get(device)).isEqualTo(1);

        var at = LocalDateTime.now();
        assertThat(exceptions.resolve(oversold, SaleExceptionStore.Resolution.RESOLVED, "Counted", 1L, "Canteen Admin", at)).isTrue();
        assertThat(exceptions.resolve(oversold, SaleExceptionStore.Resolution.RESOLVED, "Again", 1L, "Canteen Admin", at)).isFalse();
        var resolved = exceptions.find(oversold).orElseThrow();
        assertThat(resolved.open()).isFalse();
        assertThat(resolved.resolutionNote()).isEqualTo("Counted");
        assertThat(exceptions.count(review)).isZero();
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
