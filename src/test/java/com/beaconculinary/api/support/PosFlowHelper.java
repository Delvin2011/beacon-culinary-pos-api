package com.beaconculinary.api.support;

import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real POS endpoints (catalog setup, shifts, orders, kitchen status, adjustments) for
 * the POS Oversight tests, which need several of them chained per scenario. Mirrors the private
 * helpers of ShiftCloseCashAttributionIntegrationTests; the older test classes keep their own.
 */
public class PosFlowHelper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mockMvc;
    private final Clock clock;
    private final DailyMealOptionRepository dailyMealOptionRepository;
    private final DailyComponentStockRepository dailyComponentStockRepository;
    private final String adminToken;
    private final String kitchenToken;

    public PosFlowHelper(MockMvc mockMvc, Clock clock, DailyMealOptionRepository dailyMealOptionRepository,
                         DailyComponentStockRepository dailyComponentStockRepository) throws Exception {
        this.mockMvc = mockMvc;
        this.clock = clock;
        this.dailyMealOptionRepository = dailyMealOptionRepository;
        this.dailyComponentStockRepository = dailyComponentStockRepository;
        this.adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        this.kitchenToken = AuthTestHelper.loginAsKitchen(mockMvc);
    }

    public static JsonNode json(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }

    public long periodId(String name) throws Exception {
        var response = mockMvc.perform(get("/meal-periods")).andReturn().getResponse().getContentAsString();
        for (var node : MAPPER.readTree(response)) {
            if (node.get("name").asText().equalsIgnoreCase(name)) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException(name + " period not seeded");
    }

    public long createComponent(String name, String extraPrice) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"extraPrice\":" + extraPrice + "}";
        return createdId(mockMvc.perform(post("/admin/component-catalog").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)));
    }

    public long createMealCatalog(String name, String price, long componentId) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"categoryId\":" + CatalogTestHelper.setMealsId(mockMvc) + ",\"description\":\"desc\",\"price\":" + price
                + ",\"componentIds\":[" + componentId + "]}";
        return createdId(mockMvc.perform(post("/admin/meal-catalog").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)));
    }

    public long createDailyOption(long periodId, long mealCatalogId, int plannedPortions) throws Exception {
        var body = "{\"mealPeriodId\":" + periodId + ",\"optionDate\":\"" + LocalDate.now(clock) + "\",\"mealCatalogId\":"
                + mealCatalogId + ",\"plannedPortions\":" + plannedPortions + "}";
        var id = createdId(mockMvc.perform(post("/admin/daily-options").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)));
        DailyPlanTestHelper.markReady(dailyMealOptionRepository, id);
        return id;
    }

    public long createDailyComponentStock(long periodId, long componentCatalogId, int bufferQuantity) throws Exception {
        var body = "{\"componentCatalogId\":" + componentCatalogId + ",\"mealPeriodId\":" + periodId
                + ",\"optionDate\":\"" + LocalDate.now(clock) + "\",\"bufferQuantity\":" + bufferQuantity + "}";
        var id = createdId(mockMvc.perform(post("/admin/daily-component-stock").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)));
        DailyPlanTestHelper.markReady(dailyComponentStockRepository, id);
        return id;
    }

    public record ExtraReq(long dailyComponentStockId, int quantity) {
    }

    public record LineReq(long dailyMealOptionId, int quantity, List<ExtraReq> extras) {
        public LineReq(long dailyMealOptionId) {
            this(dailyMealOptionId, 1, List.of());
        }
    }

    public record PaymentReq(String method, BigDecimal amount, BigDecimal amountTendered, String cardReference) {
        public static PaymentReq cash(String amount) {
            var value = new BigDecimal(amount);
            return new PaymentReq("CASH", value, value.add(new BigDecimal("500.00")), null);
        }

        public static PaymentReq card(String amount, String cardReference) {
            return new PaymentReq("CARD", new BigDecimal(amount), null, cardReference);
        }
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private record OrderReq(List<PaymentReq> payments, List<LineReq> lines, DiscountReq discount,
                            ComplimentaryReq complimentary) {
    }

    /** The complimentary object on POST /orders. Values are text so tests can send invalid ones. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ComplimentaryReq(String reasonCode, String note, DiscountAuth authorization) {
        public static ComplimentaryReq staffMeal(String authorizationToken) {
            return new ComplimentaryReq("STAFF_MEAL", null, DiscountAuth.token(authorizationToken));
        }
    }

    /** A discount at sale on POST /orders. Values are text so tests can send invalid ones. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record DiscountReq(String type, BigDecimal value, String reasonCode, String note, DiscountAuth authorization) {
        public static DiscountReq fixed(String value, String authorizationToken) {
            return new DiscountReq("FIXED_AMOUNT", new BigDecimal(value), "MANAGER_GOODWILL", null, DiscountAuth.token(authorizationToken));
        }

        public static DiscountReq percent(String value, String authorizationToken) {
            return new DiscountReq("PERCENTAGE", new BigDecimal(value), "PROMOTION", null, DiscountAuth.token(authorizationToken));
        }
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record DiscountAuth(String authorizationToken, String sessionToken) {
        public static DiscountAuth token(String authorizationToken) {
            return new DiscountAuth(authorizationToken, null);
        }

        public static DiscountAuth session(String sessionToken) {
            return new DiscountAuth(null, sessionToken);
        }
    }

    public ResultActions submitOrder(String token, List<PaymentReq> payments, LineReq... lines) throws Exception {
        return submitOrder(token, payments, null, lines);
    }

    public ResultActions submitOrder(String token, List<PaymentReq> payments, DiscountReq discount, LineReq... lines) throws Exception {
        var body = MAPPER.writeValueAsString(new OrderReq(payments, List.of(lines), discount, null));
        return mockMvc.perform(post("/orders").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** A complimentary order: no payments (null leaves the field out), optionally with a discount
     * or payments to test that they're refused. */
    public ResultActions submitComplimentary(String token, ComplimentaryReq complimentary, List<PaymentReq> payments,
                                             DiscountReq discount, LineReq... lines) throws Exception {
        var body = MAPPER.writeValueAsString(new OrderReq(payments, List.of(lines), discount, complimentary));
        return mockMvc.perform(post("/orders").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    public ResultActions submitComplimentary(String token, ComplimentaryReq complimentary, LineReq... lines) throws Exception {
        return submitComplimentary(token, complimentary, null, null, lines);
    }

    /** A management session (manager PIN 654321), reusable until it expires. */
    public String authorizeSession(String callerToken) throws Exception {
        var body = MAPPER.writeValueAsString(new AuthorizeReq("654321"));
        var response = mockMvc.perform(post("/admin/authorize-session").header("Authorization", "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("sessionToken").asText();
    }

    public long placeCashOrder(String token, String amount, LineReq... lines) throws Exception {
        return createdId(submitOrder(token, List.of(PaymentReq.cash(amount)), lines));
    }

    public void patchStatus(long orderId, String status) throws Exception {
        mockMvc.perform(patch("/kitchen/orders/" + orderId + "/status").header("Authorization", "Bearer " + kitchenToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    /** PENDING -> IN_PROGRESS -> DONE, so a later WHOLE_ORDER adjustment reads as a REFUND. */
    public void markDone(long orderId) throws Exception {
        patchStatus(orderId, "IN_PROGRESS");
        patchStatus(orderId, "DONE");
    }

    private record OpenShiftReq(BigDecimal openingFloat) {
    }

    public long openShift(String token, String openingFloat) throws Exception {
        var body = MAPPER.writeValueAsString(new OpenShiftReq(new BigDecimal(openingFloat)));
        return createdId(mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)));
    }

    private record VarianceAuthReq(String reasonCode, String note, String authorizationToken) {
    }

    private record CloseShiftReq(BigDecimal countedCash, VarianceAuthReq varianceAuthorization) {
    }

    public ResultActions closeShift(String token, long shiftId, String countedCash) throws Exception {
        return closeShift(token, shiftId, countedCash, null);
    }

    /** With a pre-authorized variance, so the close succeeds whatever the expected cash turns
     * out to be (the authorization is ignored when the variance is zero). */
    public ResultActions closeShift(String token, long shiftId, String countedCash, String varianceAuthorizationToken) throws Exception {
        var varianceAuth = varianceAuthorizationToken == null ? null
                : new VarianceAuthReq("CASH_COUNTING_ERROR", null, varianceAuthorizationToken);
        var body = MAPPER.writeValueAsString(new CloseShiftReq(new BigDecimal(countedCash), varianceAuth));
        return mockMvc.perform(post("/shifts/" + shiftId + "/close").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private record AuthorizeReq(String pin) {
    }

    /** The admin's manager PIN (654321), submitted from the caller's terminal session. */
    public String authorizePin(String callerToken) throws Exception {
        var body = MAPPER.writeValueAsString(new AuthorizeReq("654321"));
        var response = mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("authorizationToken").asText();
    }

    private record AdjustmentReq(String scope, String reasonCode, String note, String authorizationToken) {
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private record RawAdjustmentReq(String requestedAction, String scope, String reasonCode, String discountType,
                                    BigDecimal discountValue, String authorizationToken) {
    }

    /** POST /orders/{id}/adjustments with a chosen token, scope and requestedAction (null = void/refund),
     * without asserting the outcome. */
    public ResultActions adjustWith(String token, long orderId, String scope, String requestedAction, String authorizationToken)
            throws Exception {
        var isDiscount = "DISCOUNT".equals(requestedAction);
        var body = MAPPER.writeValueAsString(new RawAdjustmentReq(requestedAction, scope, "CUSTOMER_COMPLAINT",
                isDiscount ? "FIXED_AMOUNT" : null, isDiscount ? new BigDecimal("5.00") : null, authorizationToken));
        return mockMvc.perform(post("/orders/" + orderId + "/adjustments").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    public void adjust(String token, long orderId, String scope) throws Exception {
        var body = MAPPER.writeValueAsString(new AdjustmentReq(scope, "CUSTOMER_COMPLAINT", null, authorizePin(token)));
        mockMvc.perform(post("/orders/" + orderId + "/adjustments").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private static long createdId(ResultActions result) throws Exception {
        return json(result.andExpect(status().isCreated())).get("id").asLong();
    }
}
