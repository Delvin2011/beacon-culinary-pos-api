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
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O1: POST /orders with clientOrderId. A repeat returns the same sale (200) and applies stock,
 * payments and events once; a reused key with another payload is 409 IDEMPOTENCY_CONFLICT. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class OrderIdempotencyIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ShiftRepository shiftRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired
    private OrderAdjustmentRepository orderAdjustmentRepository;
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
    @Autowired
    private MutableClock clock;

    private PosFlowHelper pos;
    private String adminToken;
    private String cashierToken;
    private String cashierBToken;
    private long optionId;
    private long extraId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window (12:00-14:30)
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        cashierBToken = AuthTestHelper.loginWithPin(mockMvc, AuthTestHelper.cashierBId(mockMvc), "654321");
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var lunchId = pos.periodId("lunch");
        var componentId = pos.createComponent("Beef", "15.00");
        var mealId = pos.createMealCatalog("Meal", "50.00", componentId);
        optionId = pos.createDailyOption(lunchId, mealId, 200);
        extraId = pos.createDailyComponentStock(lunchId, componentId, 10);
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
    }

    /** Two meals with one extra each: 2 x 50 + 2 x 15 = 130, paid in cash from 200. */
    private Map<String, Object> sale(UUID clientOrderId, int quantity, Object amount) {
        var body = new LinkedHashMap<String, Object>();
        if (clientOrderId != null) {
            body.put("clientOrderId", clientOrderId.toString());
        }
        body.put("lines", List.of(Map.of("dailyMealOptionId", optionId, "quantity", quantity,
                "extras", List.of(Map.of("dailyComponentStockId", extraId, "quantity", quantity)))));
        body.put("payments", List.of(Map.of("method", "CASH", "amount", amount, "amountTendered", 200)));
        return body;
    }

    private ResultActions post(String token, Object body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body)));
    }

    private int portionsRemaining() {
        return dailyMealOptionRepository.findById(optionId).orElseThrow().getPortionsRemaining();
    }

    private int bufferRemaining() {
        return dailyComponentStockRepository.findById(extraId).orElseThrow().getBufferRemaining();
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private void assertAppliedOnce(UUID clientOrderId) {
        assertThat(count("SELECT COUNT(*) FROM orders WHERE client_order_id = ?", clientOrderId.toString())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM order_payments")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM order_status_events")).isEqualTo(1);
        assertThat(portionsRemaining()).isEqualTo(198);
        assertThat(bufferRemaining()).isEqualTo(8);
    }

    @Test
    void duplicateSubmit_returnsTheSameOrderWith200_andAppliesStockPaymentsAndEventsOnce() throws Exception {
        pos.openShift(cashierToken, "500.00");
        var key = UUID.randomUUID();

        var first = PosFlowHelper.json(post(cashierToken, sale(key, 2, 130)).andExpect(status().isCreated())
                .andExpect(header().doesNotExist(OrderController.IDEMPOTENT_REPLAY_HEADER)));
        var second = PosFlowHelper.json(post(cashierToken, sale(key, 2, 130)).andExpect(status().isOk())
                .andExpect(header().string(OrderController.IDEMPOTENT_REPLAY_HEADER, "true"))
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/orders/" + first.get("id").asLong()))));

        assertThat(second.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(second.get("orderNumber").asInt()).isEqualTo(first.get("orderNumber").asInt());
        assertThat(second.get("total").decimalValue()).isEqualByComparingTo("130.00");
        assertThat(second.get("payments")).hasSize(1);
        assertThat(second.get("payments").get(0).get("changeDue").decimalValue()).isEqualByComparingTo("70.00");
        assertAppliedOnce(key);
    }

    @Test
    void replay_matchesWhateverTheNumbersAreWrittenAs() throws Exception {
        pos.openShift(cashierToken, "500.00");
        var key = UUID.randomUUID();
        post(cashierToken, sale(key, 2, 130)).andExpect(status().isCreated());

        post(cashierToken, sale(key, 2, "130.00")).andExpect(status().isOk());
        assertAppliedOnce(key);
    }

    @Test
    void sameKeyWithADifferentPayload_is409IdempotencyConflict_andChangesNothing() throws Exception {
        pos.openShift(cashierToken, "500.00");
        var key = UUID.randomUUID();
        post(cashierToken, sale(key, 2, 130)).andExpect(status().isCreated());

        post(cashierToken, sale(key, 1, 65)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertAppliedOnce(key);
    }

    @Test
    void replay_afterTheShiftClosed_stillReturnsTheSale() throws Exception {
        var shiftId = pos.openShift(cashierToken, "500.00");
        var key = UUID.randomUUID();
        var id = PosFlowHelper.json(post(cashierToken, sale(key, 2, 130)).andExpect(status().isCreated())).get("id").asLong();
        pos.closeShift(cashierToken, shiftId, "630.00").andExpect(status().isOk());

        post(cashierToken, sale(key, 2, 130)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertAppliedOnce(key);
    }

    @Test
    void replay_withADiscount_needsNoNewManagerPin() throws Exception {
        pos.openShift(cashierToken, "500.00");
        var key = UUID.randomUUID();
        var body = sale(key, 2, 120);
        body.put("discount", Map.of("type", "FIXED_AMOUNT", "value", 10, "reasonCode", "STAFF",
                "authorization", Map.of("authorizationToken", pos.authorizePin(cashierToken))));
        post(cashierToken, body).andExpect(status().isCreated());

        // The token was used by the first call; the retry is answered from the stored sale.
        post(cashierToken, body).andExpect(status().isOk()).andExpect(jsonPath("$.saleDiscount").value(10.0));
        // A retry that carries a fresh token is still the same sale.
        body.put("discount", Map.of("type", "FIXED_AMOUNT", "value", 10, "reasonCode", "STAFF",
                "authorization", Map.of("authorizationToken", pos.authorizePin(cashierToken))));
        post(cashierToken, body).andExpect(status().isOk());

        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM order_discounts")).isEqualTo(1);
    }

    @Test
    void replay_byAnotherCashier_is409_butAnAdminGetsTheSale() throws Exception {
        pos.openShift(cashierToken, "500.00");
        var key = UUID.randomUUID();
        post(cashierToken, sale(key, 2, 130)).andExpect(status().isCreated());

        post(cashierBToken, sale(key, 2, 130)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        post(adminToken, sale(key, 2, 130)).andExpect(status().isOk());
        assertAppliedOnce(key);
    }

    @Test
    void withoutAKey_eachSubmitIsANewSale_asBefore() throws Exception {
        pos.openShift(cashierToken, "500.00");

        post(cashierToken, sale(null, 2, 130)).andExpect(status().isCreated());
        post(cashierToken, sale(null, 2, 130)).andExpect(status().isCreated());

        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE client_order_id IS NULL")).isEqualTo(2);
    }

    @Test
    void anInvalidKey_is400() throws Exception {
        pos.openShift(cashierToken, "500.00");
        var body = sale(null, 2, 130);
        body.put("clientOrderId", "not-a-uuid");

        post(cashierToken, body).andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
    }

    @Test
    void concurrentDuplicates_createOneSale_andTheOtherCallReturnsIt() throws Exception {
        pos.openShift(cashierToken, "500.00");

        for (var run = 0; run < 5; run++) {
            var key = UUID.randomUUID();
            var body = sale(key, 1, 65);
            var start = new CountDownLatch(1);
            Callable<int[]> submit = () -> {
                start.await();
                var response = post(cashierToken, body).andReturn().getResponse();
                return new int[]{response.getStatus(), MAPPER.readTree(response.getContentAsString()).get("id").asInt()};
            };

            var pool = Executors.newFixedThreadPool(2);
            try {
                var a = pool.submit(submit);
                var b = pool.submit(submit);
                start.countDown();
                var results = List.of(a.get(), b.get());
                var statuses = new ArrayList<Integer>();
                results.forEach(result -> statuses.add(result[0]));
                assertThat(statuses).as("run %d", run).containsExactlyInAnyOrder(201, 200);
                assertThat(results.get(0)[1]).as("same order, run %d", run).isEqualTo(results.get(1)[1]);
            } finally {
                pool.shutdownNow();
            }
            assertThat(count("SELECT COUNT(*) FROM orders WHERE client_order_id = ?", key.toString())).isEqualTo(1);
        }

        // Five sales of one meal and one extra each, every one applied once.
        assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM order_payments")).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM order_status_events")).isEqualTo(5);
        assertThat(portionsRemaining()).isEqualTo(195);
        assertThat(bufferRemaining()).isEqualTo(5);
    }
}
