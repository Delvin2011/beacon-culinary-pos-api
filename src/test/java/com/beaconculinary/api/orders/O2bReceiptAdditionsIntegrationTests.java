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
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O2b additions (contract section 8.2): receiptNumber on every order, and line and extra names
 * stored at sale.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O2bReceiptAdditionsIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    private long stockId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var componentId = pos.createComponent("O2b Extra Beef", "15.00");
        var mealId = pos.createMealCatalog("O2b Beef Stew", "50.00", componentId);
        var periodId = pos.periodId("lunch");
        optionId = pos.createDailyOption(periodId, mealId, 100);
        stockId = pos.createDailyComponentStock(periodId, componentId, 50);
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
        clock.reset();
    }

    // ---- receiptNumber ----

    @Test
    void receiptNumber_isTheDateAndThePaddedOrderNumber() {
        assertThat(ReceiptNumbers.of(LocalDate.of(2026, 11, 18), 37)).isEqualTo("20261118-0037");
        assertThat(ReceiptNumbers.of(LocalDate.of(2026, 1, 2), 1)).isEqualTo("20260102-0001");
        assertThat(ReceiptNumbers.of(LocalDate.of(2026, 11, 18), 9999)).isEqualTo("20261118-9999");
        assertThat(ReceiptNumbers.of(LocalDate.of(2026, 11, 18), 10234)).isEqualTo("20261118-10234");
        assertThat(ReceiptNumbers.of(null, 1)).isNull();
        assertThat(ReceiptNumbers.of(LocalDate.of(2026, 11, 18), null)).isNull();
    }

    @Test
    void receiptNumber_isInThe201_theGet_andTheReprint() throws Exception {
        pos.openShift(cashierToken, "500.00");

        var created = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId)));
        var orderId = created.get("id").asLong();
        var expected = ReceiptNumbers.of(LocalDate.parse(created.get("orderDate").asText()), created.get("orderNumber").asInt());
        var fetched = body(mockMvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + cashierToken)));
        var reprint = body(mockMvc.perform(post("/admin/orders/" + orderId + "/reprint-receipt")
                .header("Authorization", "Bearer " + adminToken)));

        assertThat(expected).matches("\\d{8}-\\d{4,}");
        for (var order : List.of(created, fetched, reprint)) {
            assertThat(order.get("receiptNumber").asText()).isEqualTo(expected);
        }
    }

    @Test
    void receiptNumbers_differForEachOrder() throws Exception {
        pos.openShift(cashierToken, "500.00");

        var first = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId)));
        var second = body(pos.submitOrder(cashierToken, List.of(PaymentReq.cash("50.00")), new LineReq(optionId)));

        assertThat(first.get("receiptNumber").asText()).isNotEqualTo(second.get("receiptNumber").asText());
    }

    // ---- line names stored at sale ----

    @Test
    void lineAndExtraNames_areStoredAtSale() throws Exception {
        pos.openShift(cashierToken, "500.00");

        var order = body(submitWithExtra());
        var lineId = order.get("lines").get(0).get("id").asLong();

        assertThat(jdbc.queryForObject("SELECT [name] FROM [order_lines] WHERE [id] = ?", String.class, lineId))
                .isEqualTo("O2b Beef Stew");
        assertThat(jdbc.queryForObject("SELECT [component_name] FROM [order_line_extras] WHERE [order_line_id] = ?",
                String.class, lineId)).isEqualTo("O2b Extra Beef");
        assertThat(order.get("lines").get(0).get("name").asText()).isEqualTo("O2b Beef Stew");
        assertThat(order.get("lines").get(0).get("extras").get(0).get("componentName").asText()).isEqualTo("O2b Extra Beef");
    }

    @Test
    void afterARename_anOldOrderKeepsTheNamesItWasSoldWith() throws Exception {
        pos.openShift(cashierToken, "500.00");
        var orderId = body(submitWithExtra()).get("id").asLong();

        jdbc.update("UPDATE [daily_meal_options] SET [name] = 'Renamed Stew' WHERE [id] = ?", optionId);
        jdbc.update("UPDATE [component_catalog] SET [name] = 'Renamed Beef' WHERE [name] = 'O2b Extra Beef'");

        for (var order : List.of(
                body(mockMvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + cashierToken))),
                body(mockMvc.perform(post("/admin/orders/" + orderId + "/reprint-receipt")
                        .header("Authorization", "Bearer " + adminToken))))) {
            var line = order.get("lines").get(0);
            assertThat(line.get("name").asText()).isEqualTo("O2b Beef Stew");
            assertThat(line.get("extras").get(0).get("componentName").asText()).isEqualTo("O2b Extra Beef");
        }
    }

    @Test
    void anOrderFromBeforeTheChange_showsTheCurrentNames() throws Exception {
        pos.openShift(cashierToken, "500.00");
        var order = body(submitWithExtra());
        var orderId = order.get("id").asLong();
        var lineId = order.get("lines").get(0).get("id").asLong();
        jdbc.update("UPDATE [order_lines] SET [name] = NULL WHERE [id] = ?", lineId);
        jdbc.update("UPDATE [order_line_extras] SET [component_name] = NULL WHERE [order_line_id] = ?", lineId);
        jdbc.update("UPDATE [daily_meal_options] SET [name] = 'Renamed Stew' WHERE [id] = ?", optionId);
        jdbc.update("UPDATE [component_catalog] SET [name] = 'Renamed Beef' WHERE [name] = 'O2b Extra Beef'");

        var fetched = body(mockMvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + cashierToken)));

        var line = fetched.get("lines").get(0);
        assertThat(line.get("name").asText()).isEqualTo("Renamed Stew");
        assertThat(line.get("extras").get(0).get("componentName").asText()).isEqualTo("Renamed Beef");
    }

    // ---- helpers ----

    private ResultActions submitWithExtra() throws Exception {
        return pos.submitOrder(cashierToken, List.of(PaymentReq.cash("65.00")),
                new LineReq(optionId, 1, List.of(new ExtraReq(stockId, 1))));
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
