package com.beaconculinary.api.orders;

import com.beaconculinary.api.accounts.AccountRepository;
import com.beaconculinary.api.admin.AuthorizationTokenRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises Stage 4 Part C (every VOID/REFUND/DISCOUNT pays out as CASH or ACCOUNT_BALANCE,
 * computed automatically from the order's payment mix) and Part D (the reworked expected-cash
 * formula against card-only, split, and account orders — not just the single-cash-method case
 * already covered elsewhere). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class RefundPayoutIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired
    private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired
    private AuthorizationTokenRepository authorizationTokenRepository;
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
    private AccountRepository accountRepository;
    @Autowired
    private MutableClock clock;

    private String adminToken;
    private String cashierToken;
    private String kitchenToken;
    private long lunchId;
    private long optionId;
    private boolean shiftOpened = false;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        kitchenToken = AuthTestHelper.loginAsKitchen(mockMvc);
        lunchId = periodId("lunch");

        var beefId = createComponent("Beef", "15.00");
        var mealId = createMealCatalog("Potatoes & Beef", "100.00", beefId);
        optionId = createDailyOption(lunchId, mealId, 20);
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

    private void openShift() throws Exception {
        if (shiftOpened) {
            return;
        }
        mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingFloat\":500.00}"))
                .andExpect(status().isCreated());
        shiftOpened = true;
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
        static PaymentReq cash(String amount) {
            return new PaymentReq("CASH", new BigDecimal(amount), new BigDecimal(amount), null, null);
        }

        static PaymentReq card(String amount) {
            return new PaymentReq("CARD", new BigDecimal(amount), null, "AUTH-1", null);
        }

        static PaymentReq account(String amount, Long accountId) {
            return new PaymentReq("ACCOUNT", new BigDecimal(amount), null, null, accountId);
        }
    }

    private record OrderReq(List<PaymentReq> payments, List<LineReq> lines) {
    }

    private long placeOrder(PaymentReq... payments) throws Exception {
        openShift();
        var body = MAPPER.writeValueAsString(new OrderReq(List.of(payments), List.of(new LineReq(optionId, 1, List.of()))));
        var response = mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private void patchStatus(long orderId, String status) throws Exception {
        mockMvc.perform(patch("/kitchen/orders/" + orderId + "/status").header("Authorization", "Bearer " + kitchenToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private record AuthorizeReq(String pin) {
    }

    private String authorize() throws Exception {
        var body = MAPPER.writeValueAsString(new AuthorizeReq("654321"));
        var response = mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("authorizationToken").asText();
    }

    private record CancelReq(String scope, String reasonCode, String note, String authorizationToken) {
    }

    private ResultActions voidOrRefund(long orderId, String token) throws Exception {
        var body = MAPPER.writeValueAsString(new CancelReq("WHOLE_ORDER", "CUSTOMER_COMPLAINT", null, token));
        return mockMvc.perform(post("/orders/" + orderId + "/adjustments").header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private record DiscountReq(String requestedAction, String discountType, BigDecimal discountValue,
                                String reasonCode, String note, String authorizationToken) {
    }

    private ResultActions discount(long orderId, BigDecimal amount, String token) throws Exception {
        var body = MAPPER.writeValueAsString(new DiscountReq("DISCOUNT", "FIXED_AMOUNT", amount, "CUSTOMER_COMPLAINT", null, token));
        return mockMvc.perform(post("/orders/" + orderId + "/adjustments").header("Authorization", "Bearer " + cashierToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void refundOnCardOnlyOrder_paysOutAsCash_fullAmount() throws Exception {
        var orderId = placeOrder(PaymentReq.card("100.00"));
        patchStatus(orderId, "IN_PROGRESS");
        patchStatus(orderId, "DONE");

        var token = authorize();
        voidOrRefund(orderId, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.adjustments[0].action").value("REFUND"))
                .andExpect(jsonPath("$.adjustments[0].refundMethod").value("CASH"))
                .andExpect(jsonPath("$.adjustments[0].amount").value(100.00));
    }

    @Test
    void refundOnCashCardSplitOrder_paysOutAsCash_fullAmountEvenThoughPartWasCard() throws Exception {
        var orderId = placeOrder(PaymentReq.cash("60.00"), PaymentReq.card("40.00"));
        patchStatus(orderId, "IN_PROGRESS");
        patchStatus(orderId, "DONE");

        var token = authorize();
        voidOrRefund(orderId, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adjustments[0].refundMethod").value("CASH"))
                .andExpect(jsonPath("$.adjustments[0].amount").value(100.00));
    }

    @Test
    void refundOnAccountOrder_creditsAccountBalance_withZeroCashDrawerImpact() throws Exception {
        var accountId = createAccount();
        var orderId = placeOrder(PaymentReq.account("100.00", accountId));
        patchStatus(orderId, "IN_PROGRESS");
        patchStatus(orderId, "DONE");

        var shiftId = shiftRepository.findFirstByStatus(ShiftStatus.OPEN).orElseThrow().getId();
        // Before the refund: an ACCOUNT-only order never touched the cash drawer.
        mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(jsonPath("$.cashSalesTotal").value(0.00))
                .andExpect(jsonPath("$.expectedCash").value(500.00));

        var token = authorize();
        voidOrRefund(orderId, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adjustments[0].action").value("REFUND"))
                .andExpect(jsonPath("$.adjustments[0].refundMethod").value("ACCOUNT_BALANCE"))
                .andExpect(jsonPath("$.adjustments[0].accountId").value(accountId));

        mockMvc.perform(get("/admin/accounts/" + accountId + "/balance").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalReversed").value(100.00))
                .andExpect(jsonPath("$.outstandingBalance").value(0.00));

        // After the refund: still zero cash-drawer impact — the ACCOUNT_BALANCE payout never
        // touches expectedCash, unlike a CASH-refund-method adjustment would.
        mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(jsonPath("$.cashSalesTotal").value(0.00))
                .andExpect(jsonPath("$.adjustmentsTotal").value(0.00))
                .andExpect(jsonPath("$.expectedCash").value(500.00));
    }

    @Test
    void discountOnCardOnlyOrder_sameCashOutBehaviorAsRefund() throws Exception {
        var orderId = placeOrder(PaymentReq.card("100.00"));
        var token = authorize();

        // Counterintuitive but intentional (Stage 4 Part C's worked example): the customer paid
        // by card, but the cashier physically hands back cash from the drawer for the discount,
        // so it reduces expectedCash even though the sale itself contributed zero cash.
        discount(orderId, new BigDecimal("15.00"), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.total").value(85.00))
                .andExpect(jsonPath("$.adjustments[0].action").value("DISCOUNT"))
                .andExpect(jsonPath("$.adjustments[0].refundMethod").value("CASH"));

        var shiftId = shiftRepository.findFirstByStatus(ShiftStatus.OPEN).orElseThrow().getId();
        mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(jsonPath("$.cashSalesTotal").value(0.00))
                .andExpect(jsonPath("$.adjustmentsTotal").value(15.00))
                .andExpect(jsonPath("$.expectedCash").value(485.00));
    }

    @Test
    void mixedShift_cashCardSplitAndAccountOrders_expectedCashReflectsOnlyCashSalesMinusCashRefunds() throws Exception {
        // Cash-only 100.00 sale (optionId's meal is fixed at 100.00, set up in @BeforeEach).
        placeOrder(PaymentReq.cash("100.00"));
        // Card-only 100.00 sale, later refunded in full — contributes 0 to cashSalesTotal but
        // -100.00 to adjustmentsTotal (CASH refund_method), since the cash comes back from the
        // drawer regardless of how the sale itself was paid.
        var cardOrderId = placeOrder(PaymentReq.card("100.00"));
        patchStatus(cardOrderId, "IN_PROGRESS");
        patchStatus(cardOrderId, "DONE");
        voidOrRefund(cardOrderId, authorize()).andExpect(status().isCreated());
        // Cash+card split: 60.00 cash contributes to cashSalesTotal, 40.00 card doesn't.
        placeOrder(PaymentReq.cash("60.00"), PaymentReq.card("40.00"));
        // Account-only sale, later refunded — zero cash-drawer impact both ways.
        var accountId = createAccount();
        var accountOrderId = placeOrder(PaymentReq.account("100.00", accountId));
        voidOrRefund(accountOrderId, authorize()).andExpect(status().isCreated());

        var shiftId = shiftRepository.findFirstByStatus(ShiftStatus.OPEN).orElseThrow().getId();

        // cashSalesTotal = 100.00 (cash-only) + 60.00 (split's cash leg) = 160.00
        // adjustmentsTotal = 100.00 (the card-only order's CASH-payout refund) — the account
        // refund is excluded entirely (refund_method = ACCOUNT_BALANCE)
        // expectedCash = 500.00 + 160.00 - 100.00 = 560.00
        mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashSalesTotal").value(160.00))
                .andExpect(jsonPath("$.adjustmentsTotal").value(100.00))
                .andExpect(jsonPath("$.expectedCash").value(560.00));
    }
}
