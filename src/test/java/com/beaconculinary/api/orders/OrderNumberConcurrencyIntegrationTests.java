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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** O1: order numbers come from MAX+1 under a per-day lock, so concurrent sales from two tills
 * get distinct, gap-free numbers and never a 500. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class OrderNumberConcurrencyIntegrationTests {
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
    private long optionId;
    // One meal per concurrent sale. Sales of the same meal already queue on that meal's portion
    // row, which would hide the race; sales of different meals (or of always-on items, which
    // decrement nothing) share no row and race straight to the order number.
    private final List<Long> optionIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window (12:00-14:30)
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var lunchId = pos.periodId("lunch");
        var componentId = pos.createComponent("Beef", "15.00");
        optionIds.clear();
        for (var i = 0; i < 10; i++) {
            optionIds.add(pos.createDailyOption(lunchId, pos.createMealCatalog("Meal " + i, "50.00", componentId), 200));
        }
        optionId = optionIds.get(0);
        // The cashier holds the till; an ADMIN may sell on it too, so these are "two tills".
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

    private String saleBody(UUID clientOrderId, long dailyMealOptionId) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        if (clientOrderId != null) {
            body.put("clientOrderId", clientOrderId.toString());
        }
        body.put("lines", List.of(Map.of("dailyMealOptionId", dailyMealOptionId, "quantity", 1)));
        body.put("payments", List.of(Map.of("method", "CASH", "amount", 50, "amountTendered", 50)));
        return MAPPER.writeValueAsString(body);
    }

    /** Status and order number of one POST /orders. */
    private int[] submit(String token, String body) throws Exception {
        var response = mockMvc.perform(MockMvcRequestBuilders.post("/orders").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        var json = MAPPER.readTree(response.getContentAsString());
        return new int[]{response.getStatus(), json.has("orderNumber") ? json.get("orderNumber").asInt() : -1};
    }

    /** The lock must prevent clashes, not leave them to the retry in OrderSubmissionService. */
    private static void assertNoNumberClash(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain(OrderSubmissionService.ORDER_NUMBER_INDEX);
    }

    @Test
    void tenConcurrentSalesFromTwoSessions_getNumbersOneToTen_withoutAClash(CapturedOutput output) throws Exception {
        var start = new CountDownLatch(1);
        var calls = new ArrayList<Callable<int[]>>();
        for (var i = 0; i < 10; i++) {
            var token = i % 2 == 0 ? cashierToken : adminToken;
            var body = saleBody(i % 3 == 0 ? null : UUID.randomUUID(), optionIds.get(i));
            calls.add(() -> {
                start.await();
                return submit(token, body);
            });
        }
        var futures = runConcurrentlyAfter(start, calls);

        assertThat(futures).allSatisfy(result -> assertThat(result[0]).isEqualTo(201));
        assertThat(futures.stream().map(result -> result[1]).sorted().toList())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 10).boxed().toList());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT order_number) FROM orders", Integer.class)).isEqualTo(10);
        assertNoNumberClash(output);
    }

    @Test
    void concurrentSalesMixedWithConcurrentRetries_numberEachSaleOnce(CapturedOutput output) throws Exception {
        var start = new CountDownLatch(1);
        var calls = new ArrayList<Callable<int[]>>();
        for (var i = 0; i < 6; i++) {
            var body = saleBody(UUID.randomUUID(), optionIds.get(i));
            for (var copy = 0; copy < 2; copy++) {
                calls.add(() -> {
                    start.await();
                    return submit(cashierToken, body);
                });
            }
        }
        var results = runConcurrentlyAfter(start, calls);

        assertThat(results.stream().filter(result -> result[0] == 201).count()).isEqualTo(6);
        assertThat(results.stream().filter(result -> result[0] == 200).count()).isEqualTo(6);
        assertThat(results.stream().map(result -> result[1]).distinct().sorted().toList())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 6).boxed().toList());
        assertThat(orderRepository.count()).isEqualTo(6);
        assertNoNumberClash(output);
    }

    @Test
    void numberingContinuesFromTheHighestNumberAlreadyStored() throws Exception {
        var first = pos.placeCashOrder(cashierToken, "50.00", new LineReq(optionId));
        // An order written outside the API (old data, a manual fix) with a higher number.
        jdbcTemplate.update("UPDATE orders SET order_number = 7 WHERE id = ?", first);

        assertThat(submit(cashierToken, saleBody(UUID.randomUUID(), optionId))).containsExactly(201, 8);
    }

    private List<int[]> runConcurrentlyAfter(CountDownLatch start, List<Callable<int[]>> calls) throws Exception {
        var pool = Executors.newFixedThreadPool(calls.size());
        try {
            var futures = new ArrayList<Future<int[]>>();
            for (var call : calls) {
                futures.add(pool.submit(call));
            }
            start.countDown();
            var results = new ArrayList<int[]>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
