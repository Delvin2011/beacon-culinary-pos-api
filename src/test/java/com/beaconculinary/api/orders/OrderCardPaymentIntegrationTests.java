package com.beaconculinary.api.orders;

import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.shifts.ShiftStatus;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises Stage 3.1's card-present payment support (now one entry of Stage 4's payments[]
 * contract) on POST /orders, and confirms it never leaks into the shift's cash-drawer figures. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class OrderCardPaymentIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired
    private ShiftRepository shiftRepository;
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

    private String adminToken;
    private String cashierToken;
    private long lunchId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window (12:00-14:30)
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        lunchId = periodId("lunch");
    }

    @AfterEach
    void tearDown() {
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        shiftRepository.deleteAll();
    }

    private long periodId(String name) throws Exception {
        var response = mockMvc.perform(get("/meal-periods")).andReturn().getResponse().getContentAsString();
        for (var node : MAPPER.readTree(response)) {
            if (node.get("name").asText().equalsIgnoreCase(name)) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException(name + " period not seeded");
    }

    private long createComponent(String name, String extraPrice) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"extraPrice\":" + extraPrice + "}";
        var response = mockMvc.perform(post("/admin/component-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long createMealCatalog(String name, String price, long componentId) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"description\":\"desc\",\"price\":" + price
                + ",\"componentIds\":[" + componentId + "]}";
        var response = mockMvc.perform(post("/admin/meal-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long createDailyOption(long periodId, long mealCatalogId, int plannedPortions) throws Exception {
        var today = LocalDate.now(clock).toString();
        var body = "{\"mealPeriodId\":" + periodId + ",\"optionDate\":\"" + today + "\",\"mealCatalogId\":" + mealCatalogId
                + ",\"plannedPortions\":" + plannedPortions + "}";
        var response = mockMvc.perform(post("/admin/daily-options").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private void openShift(String token) throws Exception {
        mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingFloat\":500.00}"))
                .andExpect(status().isCreated());
    }

    private record LineReq(long dailyMealOptionId, int quantity, List<Object> extras) {
    }

    private record PaymentReq(String method, BigDecimal amount, BigDecimal amountTendered, String cardReference) {
    }

    private record OrderReq(List<PaymentReq> payments, List<LineReq> lines) {
    }

    // Every meal used here is 50.00 x1, so the order total — and thus the required payment
    // amount — is always exactly 50.00.
    private ResultActions placeOrder(PaymentReq payment, long optionId) throws Exception {
        var body = MAPPER.writeValueAsString(new OrderReq(List.of(payment), List.of(new LineReq(optionId, 1, List.of()))));
        return mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static PaymentReq cardPayment(String cardReference) {
        return new PaymentReq("CARD", new BigDecimal("50.00"), null, cardReference);
    }

    private static PaymentReq cashPayment(BigDecimal amountTendered) {
        return new PaymentReq("CASH", new BigDecimal("50.00"), amountTendered, null);
    }

    @Test
    void cardOrder_withCardReference_succeeds_withoutAmountTenderedOrChangeDue() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        placeOrder(cardPayment("AUTH-4821"), optionId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(50.00))
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].method").value("CARD"))
                .andExpect(jsonPath("$.payments[0].cardReference").value("AUTH-4821"))
                .andExpect(jsonPath("$.payments[0].amountTendered").doesNotExist())
                .andExpect(jsonPath("$.payments[0].changeDue").doesNotExist());

        assertPortionsRemaining(optionId, 9);
    }

    @Test
    void cardOrder_withoutCardReference_returns400_andCreatesNoOrder() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        placeOrder(cardPayment(null), optionId)
                .andExpect(status().isBadRequest());

        assertPortionsRemaining(optionId, 10);
    }

    @Test
    void cardOrder_withBlankCardReference_returns400() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        placeOrder(cardPayment("   "), optionId)
                .andExpect(status().isBadRequest());
    }

    @Test
    void cashOrder_regressionUnchanged() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        placeOrder(cashPayment(new BigDecimal("100.00")), optionId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payments[0].method").value("CASH"))
                .andExpect(jsonPath("$.payments[0].amountTendered").value(100.00))
                .andExpect(jsonPath("$.payments[0].changeDue").value(50.00))
                .andExpect(jsonPath("$.payments[0].cardReference").doesNotExist());
    }

    @Test
    void mixedShift_cashAndCardOrders_shiftSummaryReflectsCashOnly() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        placeOrder(cashPayment(new BigDecimal("100.00")), optionId).andExpect(status().isCreated());
        placeOrder(cardPayment("AUTH-9911"), optionId).andExpect(status().isCreated());

        var shiftId = shiftRepository.findFirstByStatus(ShiftStatus.OPEN).orElseThrow().getId();

        // Only the 50.00 cash sale counts toward cashSalesTotal/expectedCash — the 50.00 card
        // sale is invisible to the cash-drawer formula.
        mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingFloat").value(500.00))
                .andExpect(jsonPath("$.cashSalesTotal").value(50.00))
                .andExpect(jsonPath("$.expectedCash").value(550.00));
    }

    private void assertPortionsRemaining(long optionId, int expected) throws Exception {
        var remaining = dailyMealOptionRepository.findById(optionId).orElseThrow().getPortionsRemaining();
        assertThat(remaining).isEqualTo(expected);
    }
}
