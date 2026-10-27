package com.beaconculinary.api.accounts;

import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.DailyPlanTestHelper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises Stage 4 Part B: account CRUD, the till-picker list, balance computation, and
 * account payments — plus using an account to pay for an order (Part A/B integration). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class AccountIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountPaymentRepository accountPaymentRepository;
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
        clock.setTime(LocalTime.of(12, 30));
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
        accountPaymentRepository.deleteAll();
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
        var id = MAPPER.readTree(response).get("id").asLong();
        DailyPlanTestHelper.markReady(dailyMealOptionRepository, id);
        return id;
    }

    private void openShift(String token) throws Exception {
        mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingFloat\":500.00}"))
                .andExpect(status().isCreated());
    }

    private record CreateAccountReq(String name, String department, String contactNumber, String contactEmail) {
    }

    private long createAccount(String name) throws Exception {
        var body = MAPPER.writeValueAsString(new CreateAccountReq(name, "Finance", "021 555 0100", "billing@" + name.toLowerCase() + ".test"));
        var response = mockMvc.perform(post("/admin/accounts").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private record UpdateAccountReq(String name, String department, String contactNumber, String contactEmail, boolean active) {
    }

    private record LineReq(long dailyMealOptionId, int quantity, List<Object> extras) {
    }

    private record AuthReq(String authorizationToken, String sessionToken) {
        static AuthReq pin(String token) {
            return new AuthReq(token, null);
        }
    }

    private record PaymentReq(String method, BigDecimal amount, Long accountId, AuthReq authorization) {
    }

    private record OrderReq(List<PaymentReq> payments, List<LineReq> lines) {
    }

    private record AuthorizeReq(String pin) {
    }

    /** The admin's manager PIN (654321), entered at the cashier's till. */
    private String authorizePin() throws Exception {
        return authorize("/admin/authorize", "authorizationToken");
    }

    private String authorizeSession() throws Exception {
        return authorize("/admin/authorize-session", "sessionToken");
    }

    private String authorize(String path, String field) throws Exception {
        var response = mockMvc.perform(post(path).header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(new AuthorizeReq("654321"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get(field).asText();
    }

    private ResultActions submitAccountOrder(long accountId, BigDecimal amount, long optionId, AuthReq authorization)
            throws Exception {
        var body = MAPPER.writeValueAsString(new OrderReq(
                List.of(new PaymentReq("ACCOUNT", amount, accountId, authorization)), List.of(new LineReq(optionId, 1, List.of()))));
        return mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long placeAccountOrder(long accountId, BigDecimal amount, long optionId) throws Exception {
        var response = submitAccountOrder(accountId, amount, optionId, AuthReq.pin(authorizePin()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long beefOption() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        return createDailyOption(lunchId, mealId, 10);
    }

    @Test
    void createAccount_appearsInAdminListAndTillPicker() throws Exception {
        var id = createAccount("Acme");

        mockMvc.perform(get("/admin/accounts").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].name").value("Acme"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].accountNumber").value(String.format("PM%04d", id)))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].department").value("Finance"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].contactNumber").value("021 555 0100"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].contactEmail").value("billing@acme.test"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].active").value(true));

        mockMvc.perform(get("/accounts").param("active", "true").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].name").value("Acme"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].accountNumber").value(String.format("PM%04d", id)))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].department").value("Finance"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].contactNumber").value("021 555 0100"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].contactEmail").value("billing@acme.test"));
    }

    @Test
    void createAccount_returnsGeneratedAccountNumber() throws Exception {
        var body = MAPPER.writeValueAsString(new CreateAccountReq("Beta", "Operations", "+27 21 555 0199", "ops@beta.test"));
        var response = mockMvc.perform(post("/admin/accounts").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.department").value("Operations"))
                .andExpect(jsonPath("$.contactNumber").value("+27 21 555 0199"))
                .andReturn().getResponse().getContentAsString();
        var created = MAPPER.readTree(response);
        assertEquals(
                String.format("PM%04d", created.get("id").asLong()), created.get("accountNumber").asText());
    }

    @Test
    void createAccount_missingDepartmentOrContactNumber_returns400() throws Exception {
        var body = MAPPER.writeValueAsString(new CreateAccountReq("Gamma", "", "not-a-phone", "ops@gamma.test"));
        mockMvc.perform(post("/admin/accounts").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deactivateAccount_removesItFromActiveTillPickerList() throws Exception {
        var id = createAccount("Acme");

        var updateBody = MAPPER.writeValueAsString(new UpdateAccountReq("Acme", "Finance", "021 555 0100", "billing@acme.test", false));
        mockMvc.perform(put("/admin/accounts/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/accounts").param("active", "true").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isEmpty());
    }

    @Test
    void accountOnlyOrder_increasesOutstandingBalance() throws Exception {
        var accountId = createAccount("Acme");
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        placeAccountOrder(accountId, new BigDecimal("50.00"), optionId);

        mockMvc.perform(get("/admin/accounts/" + accountId + "/balance").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCharged").value(50.00))
                .andExpect(jsonPath("$.totalReversed").value(0.00))
                .andExpect(jsonPath("$.totalPaid").value(0.00))
                .andExpect(jsonPath("$.outstandingBalance").value(50.00));
    }

    @Test
    void inactiveAccount_cannotBeUsedForAnOrder_returns400() throws Exception {
        var accountId = createAccount("Acme");
        var updateBody = MAPPER.writeValueAsString(new UpdateAccountReq("Acme", "Finance", "021 555 0100", "billing@acme.test", false));
        mockMvc.perform(put("/admin/accounts/" + accountId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk());

        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        submitAccountOrder(accountId, new BigDecimal("50.00"), optionId, AuthReq.pin(authorizePin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonexistentAccountId_returns400() throws Exception {
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "50.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        submitAccountOrder(999999999L, new BigDecimal("50.00"), optionId, AuthReq.pin(authorizePin()))
                .andExpect(status().isBadRequest());
    }

    // --- Admin approval of account payments ----------------------------------------------------

    @Test
    void accountOrder_recordsTheApprovingAdmin() throws Exception {
        var accountId = createAccount("Acme");
        var optionId = beefOption();
        openShift(cashierToken);

        var orderId = placeAccountOrder(accountId, new BigDecimal("50.00"), optionId);

        mockMvc.perform(get("/orders/" + orderId).header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].method").value("ACCOUNT"))
                .andExpect(jsonPath("$.payments[0].authorizedBy.id").value(AuthTestHelper.adminId(mockMvc)))
                .andExpect(jsonPath("$.payments[0].authorizedBy.name").value("Canteen Admin"));
    }

    @Test
    void accountOrder_withoutAuthorization_returns400_andWritesNothing() throws Exception {
        var accountId = createAccount("Acme");
        var optionId = beefOption();
        openShift(cashierToken);

        submitAccountOrder(accountId, new BigDecimal("50.00"), optionId, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PAYMENT_INVALID"));
        submitAccountOrder(accountId, new BigDecimal("50.00"), optionId, new AuthReq(authorizePin(), authorizeSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PAYMENT_INVALID"));

        mockMvc.perform(get("/admin/accounts/" + accountId + "/balance").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalCharged").value(0));
    }

    @Test
    void accountOrder_withAnInvalidOrUsedToken_returns401() throws Exception {
        var accountId = createAccount("Acme");
        var optionId = beefOption();
        openShift(cashierToken);

        submitAccountOrder(accountId, new BigDecimal("50.00"), optionId, AuthReq.pin("not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_INVALID"));

        var token = authorizePin();
        submitAccountOrder(accountId, new BigDecimal("50.00"), optionId, AuthReq.pin(token)).andExpect(status().isCreated());
        submitAccountOrder(accountId, new BigDecimal("50.00"), optionId, AuthReq.pin(token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_INVALID"));
    }

    @Test
    void accountOrder_rejectedForTheAccount_leavesTheTokenUsable() throws Exception {
        var accountId = createAccount("Acme");
        var optionId = beefOption();
        openShift(cashierToken);
        var token = authorizePin();

        submitAccountOrder(999999999L, new BigDecimal("50.00"), optionId, AuthReq.pin(token)).andExpect(status().isBadRequest());

        submitAccountOrder(accountId, new BigDecimal("50.00"), optionId, AuthReq.pin(token)).andExpect(status().isCreated());
    }

    @Test
    void accountOrder_withSessionToken_authorizesSeveralOrders() throws Exception {
        var accountId = createAccount("Acme");
        var optionId = beefOption();
        openShift(cashierToken);
        var session = new AuthReq(null, authorizeSession());

        submitAccountOrder(accountId, new BigDecimal("50.00"), optionId, session)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payments[0].authorizedBy.name").value("Canteen Admin"));
        submitAccountOrder(accountId, new BigDecimal("50.00"), optionId, session).andExpect(status().isCreated());
    }

    private record AccountPaymentReq(BigDecimal amount, String note) {
    }

    @Test
    void multiplePartialAccountPayments_produceCorrectRunningBalance() throws Exception {
        var accountId = createAccount("Acme");
        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "100.00", beefId);
        var optionId = createDailyOption(lunchId, mealId, 10);
        openShift(cashierToken);

        placeAccountOrder(accountId, new BigDecimal("100.00"), optionId);

        var firstPayment = MAPPER.writeValueAsString(new AccountPaymentReq(new BigDecimal("30.00"), "First installment"));
        mockMvc.perform(post("/admin/accounts/" + accountId + "/payments").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(firstPayment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPaid").value(30.00))
                .andExpect(jsonPath("$.outstandingBalance").value(70.00));

        var secondPayment = MAPPER.writeValueAsString(new AccountPaymentReq(new BigDecimal("70.00"), "Final settlement"));
        mockMvc.perform(post("/admin/accounts/" + accountId + "/payments").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(secondPayment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPaid").value(100.00))
                .andExpect(jsonPath("$.outstandingBalance").value(0.00));
    }

    @Test
    void balanceOnNonexistentAccount_returns404() throws Exception {
        mockMvc.perform(get("/admin/accounts/999999999/balance").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
}
