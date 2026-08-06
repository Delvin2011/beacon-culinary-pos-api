package com.beaconculinary.api.shifts;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
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
import java.time.LocalTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises /shifts/** against the seed cashiers from V13__seed_menu_and_users.sql and
 * V14__add_pin_auth_to_users.sql: Cashier A, Admin, and Cashier B (PIN-only) — used
 * specifically to test cross-cashier shift ownership and Stage 2.5's global single-open-shift
 * constraint. Stage 2.5's expected-cash-math and cross-shift-attribution scenarios (which need
 * real orders) live in {@link ShiftCloseCashAttributionIntegrationTests} instead, since those
 * require the full meal-catalog/order-creation setup this class doesn't otherwise need.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class ShiftIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ShiftRepository shiftRepository;
    @Autowired
    private AuthorizationTokenRepository authorizationTokenRepository;
    @Autowired
    private MutableClock clock;

    private String cashierAToken;
    private String cashierBToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        cashierAToken = AuthTestHelper.loginAsCashier(mockMvc);
        cashierBToken = AuthTestHelper.loginWithPin(mockMvc, AuthTestHelper.CASHIER_B_ID, "654321");
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        authorizationTokenRepository.deleteAll();
        shiftRepository.deleteAll();
    }

    private record OpenShiftReq(BigDecimal openingFloat) {
    }

    private record AuthorizeReq(String pin) {
    }

    private record VarianceAuthReq(String reasonCode, String note, String authorizationToken) {
    }

    private record CloseShiftReq(BigDecimal countedCash, VarianceAuthReq varianceAuthorization) {
    }

    private long openShift(String token, String openingFloat) throws Exception {
        var body = MAPPER.writeValueAsString(new OpenShiftReq(new BigDecimal(openingFloat)));
        var response = mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    private String authorize(String callerToken, String pin) throws Exception {
        var body = MAPPER.writeValueAsString(new AuthorizeReq(pin));
        var response = mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("authorizationToken").asText();
    }

    private ResultActions closeShift(String token, long shiftId, String countedCash, VarianceAuthReq varianceAuth) throws Exception {
        var body = MAPPER.writeValueAsString(new CloseShiftReq(new BigDecimal(countedCash), varianceAuth));
        return mockMvc.perform(post("/shifts/" + shiftId + "/close").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void openShift_thenGetCurrent_reflectsIt() throws Exception {
        openShift(cashierAToken, "500.00");

        mockMvc.perform(get("/shifts/current").header("Authorization", "Bearer " + cashierAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openingFloat").value(500.00))
                .andExpect(jsonPath("$.cashierId").value(AuthTestHelper.CASHIER_A_ID));
    }

    @Test
    void getCurrent_withNoOpenShift_returns404() throws Exception {
        mockMvc.perform(get("/shifts/current").header("Authorization", "Bearer " + cashierAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void openingSecondShiftWhileOneOpen_sameCashier_returns409() throws Exception {
        openShift(cashierAToken, "500.00");

        mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + cashierAToken)
                        .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(new OpenShiftReq(new BigDecimal("300.00")))))
                .andExpect(status().isConflict());
    }

    @Test
    void openingSecondShiftWhileOneOpen_differentCashier_returns409() throws Exception {
        // Stage 2.5 widens Stage 1.1's per-cashier rule to a system-wide one, enforced by the
        // idx_shifts_single_open filtered unique index — a *different* cashier attempting to
        // open while cashier A's shift is open must also be rejected.
        openShift(cashierAToken, "500.00");

        mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + cashierBToken)
                        .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(new OpenShiftReq(new BigDecimal("300.00")))))
                .andExpect(status().isConflict());
    }

    @Test
    void closeOwnShift_withZeroVariance_succeedsWithoutAuthorization() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");

        closeShift(cashierAToken, shiftId, "500.00", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").exists())
                .andExpect(jsonPath("$.closingCash").value(500.00))
                .andExpect(jsonPath("$.expectedCash").value(500.00))
                .andExpect(jsonPath("$.variance").value(0.00))
                .andExpect(jsonPath("$.varianceReasonCode").doesNotExist())
                .andExpect(jsonPath("$.varianceAuthorizedById").doesNotExist());
    }

    @Test
    void cashierClosingAnotherCashiersShift_returns403() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");

        closeShift(cashierBToken, shiftId, "500.00", null)
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCloseAnyCashiersShift() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");

        closeShift(adminToken, shiftId, "500.00", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void closeAlreadyClosedShift_returns409() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");
        closeShift(cashierAToken, shiftId, "500.00", null).andExpect(status().isOk());

        closeShift(cashierAToken, shiftId, "500.00", null)
                .andExpect(status().isConflict());
    }

    @Test
    void getShiftSummary_ownShift_withNoOrders_reflectsOpeningFloatOnly() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");

        mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + cashierAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingFloat").value(500.00))
                .andExpect(jsonPath("$.cashSalesTotal").value(0.00))
                .andExpect(jsonPath("$.adjustmentsTotal").value(0.00))
                .andExpect(jsonPath("$.expectedCash").value(500.00))
                .andExpect(jsonPath("$.orderCount").value(0));
    }

    @Test
    void getShiftSummary_byNonOwnerNonAdmin_returns403() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");

        mockMvc.perform(get("/shifts/" + shiftId + "/summary").header("Authorization", "Bearer " + cashierBToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void closeShift_nonzeroVariance_withoutAuthorization_returns400() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");

        closeShift(cashierAToken, shiftId, "450.00", null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void closeShift_nonzeroVariance_otherReasonWithoutNote_returns400() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");
        var token = authorize(cashierAToken, "654321");

        closeShift(cashierAToken, shiftId, "450.00", new VarianceAuthReq("OTHER", null, token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void closeShift_nonzeroVariance_withValidAuthorization_succeedsAndPersistsVarianceFields() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");
        var token = authorize(cashierAToken, "654321");

        closeShift(cashierAToken, shiftId, "450.00", new VarianceAuthReq("CASH_COUNTING_ERROR", "Counted twice, short by 50.", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closingCash").value(450.00))
                .andExpect(jsonPath("$.expectedCash").value(500.00))
                .andExpect(jsonPath("$.variance").value(-50.00))
                .andExpect(jsonPath("$.varianceReasonCode").value("CASH_COUNTING_ERROR"))
                .andExpect(jsonPath("$.varianceNote").value("Counted twice, short by 50."))
                .andExpect(jsonPath("$.varianceAuthorizedById").value(AuthTestHelper.ADMIN_ID));
    }

    @Test
    void closeShift_reusingToken_isRejectedOnSecondCloseCall() throws Exception {
        var firstShiftId = openShift(cashierAToken, "500.00");
        var token = authorize(cashierAToken, "654321");
        closeShift(cashierAToken, firstShiftId, "450.00", new VarianceAuthReq("CASH_COUNTING_ERROR", null, token))
                .andExpect(status().isOk());

        var secondShiftId = openShift(cashierAToken, "300.00");
        closeShift(cashierAToken, secondShiftId, "250.00", new VarianceAuthReq("CASH_COUNTING_ERROR", null, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void closeShift_expiredToken_isRejected_evenThoughUnused() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");
        var token = authorize(cashierAToken, "654321");
        clock.setTime(LocalTime.of(12, 31, 5)); // > 60s after the 12:30:00 authorize call

        closeShift(cashierAToken, shiftId, "450.00", new VarianceAuthReq("CASH_COUNTING_ERROR", null, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void closeShift_invalidRequestFailure_stillBurnsTheToken() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");
        var token = authorize(cashierAToken, "654321");
        // OTHER without a note fails request validation *after* the token is consumed.
        closeShift(cashierAToken, shiftId, "450.00", new VarianceAuthReq("OTHER", null, token))
                .andExpect(status().isBadRequest());

        closeShift(cashierAToken, shiftId, "450.00", new VarianceAuthReq("CASH_COUNTING_ERROR", null, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void openShift_withoutAuth_unauthorized() throws Exception {
        mockMvc.perform(post("/shifts/open").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(new OpenShiftReq(new BigDecimal("500.00")))))
                .andExpect(status().isUnauthorized());
    }
}
