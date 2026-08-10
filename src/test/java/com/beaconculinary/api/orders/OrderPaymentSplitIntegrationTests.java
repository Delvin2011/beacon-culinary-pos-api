package com.beaconculinary.api.orders;

import com.beaconculinary.api.accounts.AccountRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises Stage 4 Part A: cash+card splits and the validation rules around payments[]
 * (sum-must-equal-total, at most one entry per method, ACCOUNT mutual exclusivity). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class OrderPaymentSplitIntegrationTests {
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
    private AccountRepository accountRepository;
    @Autowired
    private MutableClock clock;

    private String adminToken;
    private String cashierToken;
    private long lunchId;
    private long optionId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        lunchId = periodId("lunch");

        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "100.00", beefId);
        optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);
    }

    @AfterEach
    void tearDown() {
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        shiftRepository.deleteAll();
        accountRepository.deleteAll();
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

    private record CreateAccountReq(String name, String contactEmail) {
    }

    private long createAccount() throws Exception {
        var body = MAPPER.writeValueAsString(new CreateAccountReq("Acme", "billing@acme.test"));
        var response = mockMvc.perform(post("/admin/accounts").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private record LineReq(long dailyMealOptionId, int quantity, List<Object> extras) {
    }

    private record PaymentReq(String method, BigDecimal amount, BigDecimal amountTendered, String cardReference, Long accountId) {
        static PaymentReq cash(String amount, String amountTendered) {
            return new PaymentReq("CASH", new BigDecimal(amount), new BigDecimal(amountTendered), null, null);
        }

        static PaymentReq card(String amount, String cardReference) {
            return new PaymentReq("CARD", new BigDecimal(amount), null, cardReference, null);
        }

        static PaymentReq account(String amount, Long accountId) {
            return new PaymentReq("ACCOUNT", new BigDecimal(amount), null, null, accountId);
        }
    }

    private record OrderReq(List<PaymentReq> payments, List<LineReq> lines) {
    }

    private ResultActions placeOrder(PaymentReq... payments) throws Exception {
        var body = MAPPER.writeValueAsString(new OrderReq(List.of(payments), List.of(new LineReq(optionId, 1, List.of()))));
        return mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void cashCardSplit_sumEqualsTotalExactly_succeeds() throws Exception {
        placeOrder(PaymentReq.cash("60.00", "60.00"), PaymentReq.card("40.00", "AUTH-777"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(100.00))
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.payments[0].method").value("CASH"))
                .andExpect(jsonPath("$.payments[0].amount").value(60.00))
                .andExpect(jsonPath("$.payments[1].method").value("CARD"))
                .andExpect(jsonPath("$.payments[1].amount").value(40.00))
                .andExpect(jsonPath("$.payments[1].cardReference").value("AUTH-777"));

        assertPortionsRemaining(9);
    }

    @Test
    void splitSumMismatch_returns400_createsNoOrder() throws Exception {
        placeOrder(PaymentReq.cash("60.00", "60.00"), PaymentReq.card("30.00", "AUTH-777"))
                .andExpect(status().isBadRequest());

        assertPortionsRemaining(10);
    }

    @Test
    void accountPlusCash_returns400() throws Exception {
        var accountId = createAccount();

        placeOrder(PaymentReq.account("50.00", accountId), PaymentReq.cash("50.00", "50.00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void accountPlusCard_returns400() throws Exception {
        var accountId = createAccount();

        placeOrder(PaymentReq.account("50.00", accountId), PaymentReq.card("50.00", "AUTH-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void twoAccountEntries_returns400() throws Exception {
        var accountId = createAccount();

        placeOrder(PaymentReq.account("50.00", accountId), PaymentReq.account("50.00", accountId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void twoCashEntries_sameMethodTwice_returns400() throws Exception {
        placeOrder(PaymentReq.cash("50.00", "50.00"), PaymentReq.cash("50.00", "50.00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void threePaymentEntries_returns400() throws Exception {
        var accountId = createAccount();

        placeOrder(PaymentReq.cash("40.00", "40.00"), PaymentReq.card("40.00", "AUTH-1"), PaymentReq.account("20.00", accountId))
                .andExpect(status().isBadRequest());
    }

    private void assertPortionsRemaining(int expected) throws Exception {
        var remaining = dailyMealOptionRepository.findById(optionId).orElseThrow().getPortionsRemaining();
        org.assertj.core.api.Assertions.assertThat(remaining).isEqualTo(expected);
    }
}
