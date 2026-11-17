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
import com.beaconculinary.api.support.PosFlowHelper.LineReq;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalTime;
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

/** O1: POST /orders/{id}/adjustments with clientRequestId. A retried void or refund returns the
 * order (200) without a second adjustment, stock restore or token. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class AdjustmentIdempotencyIntegrationTests {
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

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window (12:00-14:30)
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        cashierBToken = AuthTestHelper.loginWithPin(mockMvc, AuthTestHelper.cashierBId(mockMvc), "654321");
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var mealId = pos.createMealCatalog("Meal", "50.00", pos.createComponent("Beef", "15.00"));
        optionId = pos.createDailyOption(pos.periodId("lunch"), mealId, 200);
        pos.openShift(cashierToken, "500.00");
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

    private Map<String, Object> wholeOrder(UUID clientRequestId, String reasonCode, String authorizationToken) {
        var body = new LinkedHashMap<String, Object>();
        body.put("clientRequestId", clientRequestId.toString());
        body.put("scope", "WHOLE_ORDER");
        body.put("reasonCode", reasonCode);
        body.put("authorizationToken", authorizationToken);
        return body;
    }

    private ResultActions adjust(String token, long orderId, Object body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/orders/" + orderId + "/adjustments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body)));
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private int portionsRemaining() {
        return dailyMealOptionRepository.findById(optionId).orElseThrow().getPortionsRemaining();
    }

    @Test
    void retriedVoid_returnsTheOrderWith200_andRestoresStockOnce() throws Exception {
        var orderId = pos.placeCashOrder(cashierToken, "100.00", new LineReq(optionId, 2, List.of()));
        assertThat(portionsRemaining()).isEqualTo(198);
        var body = wholeOrder(UUID.randomUUID(), "CUSTOMER_COMPLAINT", pos.authorizePin(cashierToken));

        adjust(cashierToken, orderId, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VOIDED"));
        // The token was used by the first call; the retry is answered from the stored adjustment.
        adjust(cashierToken, orderId, body).andExpect(status().isOk())
                .andExpect(header().string(OrderController.IDEMPOTENT_REPLAY_HEADER, "true"))
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.adjustments.length()").value(1));

        assertThat(count("SELECT COUNT(*) FROM order_adjustments")).isEqualTo(1);
        assertThat(portionsRemaining()).isEqualTo(200);
    }

    @Test
    void retriedRefund_returnsTheOrderWith200_andRecordsOneRefund() throws Exception {
        var orderId = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        pos.markDone(orderId);
        var body = wholeOrder(UUID.randomUUID(), "CUSTOMER_COMPLAINT", pos.authorizePin(cashierToken));

        adjust(cashierToken, orderId, body).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REFUNDED"));
        adjust(cashierToken, orderId, body).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));

        assertThat(count("SELECT COUNT(*) FROM order_adjustments WHERE action = 'REFUND'")).isEqualTo(1);
        assertThat(count("SELECT COALESCE(SUM(amount), 0) FROM order_adjustments")).isEqualTo(50);
    }

    @Test
    void sameKeyWithADifferentReason_is409() throws Exception {
        var orderId = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        var key = UUID.randomUUID();
        adjust(cashierToken, orderId, wholeOrder(key, "CUSTOMER_COMPLAINT", pos.authorizePin(cashierToken)))
                .andExpect(status().isCreated());

        adjust(cashierToken, orderId, wholeOrder(key, "KITCHEN_ERROR", pos.authorizePin(cashierToken)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM order_adjustments")).isEqualTo(1);
    }

    @Test
    void sameKeyOnAnotherOrder_is409_andLeavesThatOrderAlone() throws Exception {
        var first = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        var second = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        var key = UUID.randomUUID();
        adjust(cashierToken, first, wholeOrder(key, "CUSTOMER_COMPLAINT", pos.authorizePin(cashierToken)))
                .andExpect(status().isCreated());

        adjust(cashierToken, second, wholeOrder(key, "CUSTOMER_COMPLAINT", pos.authorizePin(cashierToken)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(orderRepository.findById(second).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void replay_byAnotherCashier_is409_butAnAdminGetsTheOrder() throws Exception {
        var orderId = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        var body = wholeOrder(UUID.randomUUID(), "CUSTOMER_COMPLAINT", pos.authorizePin(cashierToken));
        adjust(cashierToken, orderId, body).andExpect(status().isCreated());

        adjust(cashierBToken, orderId, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        adjust(adminToken, orderId, body).andExpect(status().isOk());
    }

    @Test
    void concurrentDuplicates_withOneSingleUseToken_giveOne201AndOne200() throws Exception {
        for (var run = 0; run < 5; run++) {
            var orderId = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
            var body = wholeOrder(UUID.randomUUID(), "CUSTOMER_COMPLAINT", pos.authorizePin(cashierToken));
            var start = new CountDownLatch(1);
            Callable<Integer> submit = () -> {
                start.await();
                return adjust(cashierToken, orderId, body).andReturn().getResponse().getStatus();
            };

            var pool = Executors.newFixedThreadPool(2);
            try {
                var a = pool.submit(submit);
                var b = pool.submit(submit);
                start.countDown();
                assertThat(List.of(a.get(), b.get())).as("run %d", run).containsExactlyInAnyOrder(201, 200);
            } finally {
                pool.shutdownNow();
            }
            assertThat(count("SELECT COUNT(*) FROM order_adjustments WHERE order_id = ?", orderId)).isEqualTo(1);
        }
        // Every sale was voided once, so every portion is back.
        assertThat(portionsRemaining()).isEqualTo(200);
    }
}
