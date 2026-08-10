package com.beaconculinary.api.admin;

import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises Stage 3.3's session-scoped management-menu authorization: POST
 * /admin/authorize-session, its reuse across multiple POST /orders/{id}/adjustments calls, and
 * its ability to bypass GET /shifts/{id}/summary's usual owner-or-admin restriction. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class ManagementSessionIntegrationTests {
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
    private AuthorizationSessionRepository authorizationSessionRepository;
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

    private String cashierAToken;
    private String cashierBToken;
    private long lunchId;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30)); // inside the Lunch window (12:00-14:30)
        cashierAToken = AuthTestHelper.loginAsCashier(mockMvc);
        cashierBToken = AuthTestHelper.loginWithPin(mockMvc, AuthTestHelper.CASHIER_B_ID, "654321");
        lunchId = periodId("lunch");
    }

    @AfterEach
    void tearDown() {
        orderAdjustmentRepository.deleteAll();
        authorizationSessionRepository.deleteAll();
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

    private long periodId(String name) throws Exception {
        var response = mockMvc.perform(get("/meal-periods")).andReturn().getResponse().getContentAsString();
        for (var node : MAPPER.readTree(response)) {
            if (node.get("name").asText().equalsIgnoreCase(name)) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException(name + " period not seeded");
    }

    // Component/meal-catalog admin endpoints require ADMIN — reuse the seeded admin for setup.
    private long createComponentAsAdmin(String adminToken, String name, String extraPrice) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"extraPrice\":" + extraPrice + "}";
        var response = mockMvc.perform(post("/admin/component-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long createMealCatalog(String adminToken, String name, String price, long componentId) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"description\":\"desc\",\"price\":" + price
                + ",\"componentIds\":[" + componentId + "]}";
        var response = mockMvc.perform(post("/admin/meal-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private long createDailyOption(String adminToken, long periodId, long mealCatalogId, int plannedPortions) throws Exception {
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
        mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + cashierAToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingFloat\":500.00}"))
                .andExpect(status().isCreated());
    }

    private record LineReq(long dailyMealOptionId, int quantity, List<Object> extras) {
    }

    private record PaymentReq(String method, BigDecimal amount, BigDecimal amountTendered) {
    }

    private record OrderReq(List<PaymentReq> payments, List<LineReq> lines) {
    }

    // Every order placed via this helper is the one meal ("Meal", 50.00 x1) set up by
    // setUpOptionWithOpenShift, so the total — and thus the required payment amount — is
    // always exactly 50.00.
    private long placeOrder(long optionId) throws Exception {
        var body = MAPPER.writeValueAsString(new OrderReq(
                List.of(new PaymentReq("CASH", new BigDecimal("50.00"), new BigDecimal("500.00"))),
                List.of(new LineReq(optionId, 1, List.of()))));
        var response = mockMvc.perform(post("/orders").header("Authorization", "Bearer " + cashierAToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private record AuthorizeReq(String pin) {
    }

    private String authorizeSingleUseToken(String callerToken) throws Exception {
        var body = MAPPER.writeValueAsString(new AuthorizeReq("654321"));
        var response = mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("authorizationToken").asText();
    }

    private String authorizeSession(String callerToken) throws Exception {
        var body = MAPPER.writeValueAsString(new AuthorizeReq("654321"));
        var response = mockMvc.perform(post("/admin/authorize-session").header("Authorization", "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").exists())
                .andExpect(jsonPath("$.adminId").value(AuthTestHelper.ADMIN_ID))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("sessionToken").asText();
    }

    private ResultActions viewSummary(String callerToken, long shiftId, String sessionToken) throws Exception {
        var request = get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + callerToken);
        if (sessionToken != null) {
            request = request.param("sessionToken", sessionToken);
        }
        return mockMvc.perform(request);
    }

    private record CancelReq(String scope, String reasonCode, String note, String authorizationToken, String sessionToken) {
    }

    private ResultActions voidOrder(long orderId, String authorizationToken, String sessionToken) throws Exception {
        var body = MAPPER.writeValueAsString(new CancelReq("WHOLE_ORDER", "CUSTOMER_COMPLAINT", null, authorizationToken, sessionToken));
        return mockMvc.perform(post("/orders/" + orderId + "/adjustments").header("Authorization", "Bearer " + cashierAToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private record DiscountReq(String requestedAction, String scope, String discountType, BigDecimal discountValue,
                                String reasonCode, String note, String authorizationToken, String sessionToken) {
    }

    private ResultActions discountOrder(long orderId, String authorizationToken, String sessionToken) throws Exception {
        var body = MAPPER.writeValueAsString(new DiscountReq("DISCOUNT", null, "FIXED_AMOUNT", new BigDecimal("5.00"),
                "CUSTOMER_COMPLAINT", null, authorizationToken, sessionToken));
        return mockMvc.perform(post("/orders/" + orderId + "/adjustments").header("Authorization", "Bearer " + cashierAToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long setUpOptionWithOpenShift(String adminToken) throws Exception {
        var beefId = createComponentAsAdmin(adminToken, "Beef", "15.00");
        var mealId = createMealCatalog(adminToken, "Meal", "50.00", beefId);
        var optionId = createDailyOption(adminToken, lunchId, mealId, 10);
        openShift();
        return optionId;
    }

    private long setUpOrder(String adminToken) throws Exception {
        return placeOrder(setUpOptionWithOpenShift(adminToken));
    }

    @Test
    void authorizeSession_thenViewOwnCashupSummary_succeeds() throws Exception {
        openShift();
        var shiftId = shiftRepository.findFirstByStatus(ShiftStatus.OPEN).orElseThrow().getId();

        var sessionToken = authorizeSession(cashierAToken);

        viewSummary(cashierAToken, shiftId, sessionToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingFloat").value(500.00));
    }

    @Test
    void sessionToken_allowsCrossCashierShiftSummaryView_bypassingOwnership() throws Exception {
        openShift();
        var shiftId = shiftRepository.findFirstByStatus(ShiftStatus.OPEN).orElseThrow().getId();

        // Without a session token, cashier B viewing cashier A's shift is forbidden.
        viewSummary(cashierBToken, shiftId, null).andExpect(status().isForbidden());

        // With a valid management session token, the same call succeeds regardless of ownership.
        var sessionToken = authorizeSession(cashierBToken);
        viewSummary(cashierBToken, shiftId, sessionToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingFloat").value(500.00));
    }

    @Test
    void sameSessionToken_authorizesVoidThenDiscount_withoutReAuthorization() throws Exception {
        var adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        var optionId = setUpOptionWithOpenShift(adminToken);
        var order1 = placeOrder(optionId);
        var order2 = placeOrder(optionId);

        var sessionToken = authorizeSession(cashierAToken);

        voidOrder(order1, null, sessionToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VOIDED"));

        // Same, un-consumed session token authorizes a second, different kind of action.
        discountOrder(order2, null, sessionToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adjustments[0].action").value("DISCOUNT"));
    }

    @Test
    void expiredSessionToken_isRejected401_onAdjustmentAndOnSummary() throws Exception {
        var adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        var orderId = setUpOrder(adminToken);
        var shiftId = shiftRepository.findFirstByStatus(ShiftStatus.OPEN).orElseThrow().getId();

        var sessionToken = authorizeSession(cashierAToken);
        clock.setTime(LocalTime.of(12, 35, 1)); // > 5 minutes after the 12:30:00 authorize-session call

        voidOrder(orderId, null, sessionToken).andExpect(status().isUnauthorized());
        viewSummary(cashierAToken, shiftId, sessionToken).andExpect(status().isUnauthorized());
    }

    @Test
    void oldStyleSingleUseToken_stillWorks_regression() throws Exception {
        var adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        var orderId = setUpOrder(adminToken);

        var token = authorizeSingleUseToken(cashierAToken);
        voidOrder(orderId, token, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VOIDED"));
    }
}
