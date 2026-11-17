package com.beaconculinary.api.shifts;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.TestStaffCleanup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O2 step 4 (R4): with pos.tills.require-registered=true, the POS needs a registered till: shift
 * open, sales, adjustments and CASHIER/ADMIN PIN sign-in are refused with 403 DEVICE_NOT_REGISTERED
 * from any other browser, in device-trust mode off. Kitchen-role PIN sign-in and password sign-in
 * are not affected. (The "off" side is the rest of the suite, which runs with it off.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = "pos.tills.require-registered=true")
class O2RequireRegisteredIntegrationTests {
    private static final String PREFIX = "O2 ReqReg ";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ShiftRepository shiftRepository;

    private String adminToken;
    private String cashierToken;
    private Cookie till;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
        till = registerTill();
    }

    @AfterEach
    void tearDown() {
        shiftRepository.deleteAll();
        jdbc.update("DELETE c FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id] WHERE d.[name] LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM [devices] WHERE [name] LIKE ?", PREFIX + "%");
        TestStaffCleanup.deleteByNamePrefix(jdbc, PREFIX);
        clock.reset();
    }

    @Test
    void openShift_withoutARegisteredTill_isRefused() throws Exception {
        assertError(open(null), 403, "DEVICE_NOT_REGISTERED");
        assertThat(shiftRepository.count()).isZero();
    }

    @Test
    void openShift_onARegisteredTill_works() throws Exception {
        assertThat(open(till).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void sale_withoutARegisteredTill_isRefused_beforeTheShiftIsLookedUp() throws Exception {
        var body = Map.of("payments", List.of(Map.of("method", "CASH", "amount", new BigDecimal("50.00"))),
                "lines", List.of(Map.of("dailyMealOptionId", 1, "quantity", 1, "extras", List.of())));

        var result = mockMvc.perform(on(post("/orders"), cashierToken, null).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body))).andReturn();

        assertError(result, 403, "DEVICE_NOT_REGISTERED");
    }

    @Test
    void adjustment_withoutARegisteredTill_isRefused() throws Exception {
        var body = Map.of("scope", "WHOLE_ORDER", "reasonCode", "CUSTOMER_COMPLAINT", "authorizationToken", "unused");

        var result = mockMvc.perform(on(post("/orders/1/adjustments"), cashierToken, null).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body))).andReturn();

        assertError(result, 403, "DEVICE_NOT_REGISTERED");
    }

    @Test
    void cashierPinSignIn_withoutARegisteredTill_isRefused_andOnOneItWorks() throws Exception {
        var cashierBId = AuthTestHelper.cashierBId(mockMvc);

        assertError(pinLogin(cashierBId, "654321", null), 403, "DEVICE_NOT_REGISTERED");
        assertThat(pinLogin(cashierBId, "654321", till).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unknownCashierId_withoutARegisteredTill_looksTheSame() throws Exception {
        assertError(pinLogin(987_654_321L, "123456", null), 403, "DEVICE_NOT_REGISTERED");
    }

    @Test
    void kitchenPinSignIn_isNotGated() throws Exception {
        var pin = String.format("%06d", RANDOM.nextInt(1_000_000));
        var created = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", PREFIX + "Chef " + RANDOM.nextInt(1_000_000), "role", "HEAD_CHEF", "pin", pin,
                        "email", "o2.chef." + RANDOM.nextInt(1_000_000_000) + "@example.test",
                        "password", "Pw" + Long.toString(Math.abs(RANDOM.nextLong()), 36) + "x9Q")))).andReturn();
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);

        assertThat(pinLogin(body(created).get("id").asLong(), pin, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void passwordSignIn_isNotGated() {
        // loginAsCashier and loginAsAdmin in setUp signed in by password from a browser without a till.
        assertThat(cashierToken).isNotBlank();
        assertThat(adminToken).isNotBlank();
    }

    // ---- helpers ----

    private Cookie registerTill() throws Exception {
        var result = mockMvc.perform(post("/admin/devices").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", PREFIX + RANDOM.nextInt(1_000_000),
                        "serialNumber", "O2R" + Long.toString(Math.abs(RANDOM.nextLong()), 36).toUpperCase())))).andReturn();
        assertThat(result.getResponse().getStatus()).as("register till").isEqualTo(201);
        for (var header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith("deviceToken=")) {
                return new Cookie("deviceToken", header.substring("deviceToken=".length(), header.indexOf(';')));
            }
        }
        throw new AssertionError("no till cookie");
    }

    private MockHttpServletRequestBuilder on(MockHttpServletRequestBuilder request, String token, Cookie cookie) {
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return cookie == null ? request : request.cookie(cookie);
    }

    private MvcResult open(Cookie cookie) throws Exception {
        return mockMvc.perform(on(post("/shifts/open"), cashierToken, cookie).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openingFloat", new BigDecimal("500.00"))))).andReturn();
    }

    private MvcResult pinLogin(long userId, String pin, Cookie cookie) throws Exception {
        return mockMvc.perform(on(post("/auth/pin-login"), null, cookie).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("cashierId", userId, "pin", pin)))).andReturn();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as("status: " + result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(body(result).get("code").asText()).as("code").isEqualTo(code);
    }
}
