package com.beaconculinary.api.shifts;

import com.beaconculinary.api.endofday.DayCloseRules;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.TestLocale;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O2 step 7 (R11): force close with and without a count, the late count, confirming "no count" at
 * sign-off, Close Day's view of uncounted shifts, and the insert-only audit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O2ForceCloseIntegrationTests {
    private static final String PREFIX = "O2 Force ";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private DayCloseRules dayCloseRules;

    private final List<Long> staff = new ArrayList<>();
    private String adminPwd;
    private long adminId;
    private Account cashier;
    private Account other;
    private Account managerPin;
    private Cookie till;

    record Account(long id, String pin) {
    }

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        adminPwd = AuthTestHelper.loginAsAdmin(mockMvc);
        adminId = AuthTestHelper.adminId(mockMvc);
        cashier = createStaff("CASHIER");
        other = createStaff("CASHIER");
        managerPin = createStaff("ADMIN");
        var result = mockMvc.perform(post("/admin/devices").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", PREFIX + RANDOM.nextInt(1_000_000),
                        "serialNumber", "O2F" + Long.toString(Math.abs(RANDOM.nextLong()), 36).toUpperCase())))).andReturn();
        assertThat(result.getResponse().getStatus()).as("register till").isEqualTo(201);
        till = cookie(result, "deviceToken");
    }

    @AfterEach
    void tearDown() {
        var shifts = jdbc.queryForList("SELECT [id] FROM [shifts] WHERE [cashier_id] IN (?, ?, ?)", Long.class,
                cashier.id(), other.id(), managerPin.id());
        for (var id : shifts) {
            jdbc.execute("DISABLE TRIGGER trg_shift_cash_count_events_insert_only ON shift_cash_count_events; "
                    + "DELETE FROM shift_cash_count_events WHERE shift_id = " + id + "; "
                    + "ENABLE TRIGGER trg_shift_cash_count_events_insert_only ON shift_cash_count_events;");
            jdbc.update("DELETE FROM [shift_signoffs] WHERE [shift_id] = ?", id);
            jdbc.update("DELETE FROM [shifts] WHERE [id] = ?", id);
        }
        for (var id : staff) {
            jdbc.update("DELETE FROM [user_sessions] WHERE [user_id] = ?", id);
        }
        jdbc.update("DELETE c FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id] WHERE d.[name] LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM [devices] WHERE [name] LIKE ?", PREFIX + "%");
        TestStaffCleanup.delete(jdbc, staff);
        staff.clear();
        clock.reset();
    }

    // ---- force close ----

    @Test
    void forceClose_withAMatchingCount_closesTheShift_endsTheOwnersSessions_andIsAudited() throws Exception {
        var signIn = pinLogin(cashier, till);
        var shiftId = openShift(token(signIn));

        var result = forceClose(adminPwd, shiftId, body("countedCash", new BigDecimal("500.00"), "reason", "Went home ill"));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        var shift = body(result);
        assertThat(shift.get("status").asText()).isEqualTo("CLOSED");
        assertThat(shift.get("forceClosed").asBoolean()).isTrue();
        assertThat(shift.get("cashCount").asText()).isEqualTo("COUNTED");
        assertThat(shift.get("expectedCash").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(shift.get("variance").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT [closed_by] FROM [shifts] WHERE [id] = ?", Long.class, shiftId)).isEqualTo(adminId);
        assertThat(jdbc.queryForObject("SELECT [force_close_reason] FROM [shifts] WHERE [id] = ?", String.class, shiftId))
                .isEqualTo("Went home ill");
        assertSessionEnded(refresh(signIn), "SHIFT_FORCE_CLOSED");
        assertThat(events(shiftId)).containsExactly("FORCE_CLOSED");
    }

    @Test
    void forceClose_withAVariance_needsAReason_andTheAdminApprovesIt() throws Exception {
        var shiftId = openShift(token(pinLogin(cashier, till)));

        var missing = forceClose(adminPwd, shiftId, body("countedCash", new BigDecimal("480.00"), "reason", "Abandoned"));
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(missing).has("varianceReasonCode")).isTrue();

        var result = forceClose(adminPwd, shiftId, body("countedCash", new BigDecimal("480.00"), "reason", "Abandoned",
                "varianceReasonCode", "CASH_COUNTING_ERROR"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("variance").decimalValue()).isEqualByComparingTo("-20.00");
        assertThat(body(result).get("varianceReasonCode").asText()).isEqualTo("CASH_COUNTING_ERROR");
        assertThat(body(result).get("varianceNote").asText()).isEqualTo("Abandoned");
        assertThat(body(result).get("varianceAuthorizedById").asLong()).isEqualTo(adminId);
    }

    @Test
    void forceClose_withoutACount_leavesTheShiftUncounted_andFreesTheTill() throws Exception {
        var shiftId = openShift(token(pinLogin(cashier, till)));

        var result = forceClose(adminPwd, shiftId, body("reason", "Drawer locked away"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var shift = body(result);
        assertThat(shift.get("cashCount").asText()).isEqualTo("UNCOUNTED");
        assertThat(shift.get("closingCash").isNull()).isTrue();
        assertThat(shift.get("variance").isNull()).isTrue();
        assertThat(shift.get("expectedCash").decimalValue()).isEqualByComparingTo("500.00");
        var next = pinLogin(other, till);
        assertThat(next.getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(on(post("/shifts/open"), till).header("Authorization", "Bearer " + token(next))
                .contentType(MediaType.APPLICATION_JSON).content("{\"openingFloat\":300.00}")).andReturn().getResponse().getStatus())
                .isEqualTo(201);
    }

    @Test
    void forceClose_needsARecentPasswordSignIn() throws Exception {
        var shiftId = openShift(token(pinLogin(cashier, till)));
        var adminByPin = token(pinLogin(managerPin, null));

        var result = forceClose(adminByPin, shiftId, body("reason", "x"));

        assertError(result, 403, "REAUTH_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT [status] FROM [shifts] WHERE [id] = ?", String.class, shiftId)).isEqualTo("OPEN");
    }

    @Test
    void forceClose_refusals() throws Exception {
        var cashierToken = token(pinLogin(cashier, till));
        var shiftId = openShift(cashierToken);

        assertThat(forceClose(cashierToken, shiftId, body("reason", "x")).getResponse().getStatus()).isEqualTo(403);
        assertThat(forceClose(adminPwd, 987_654_321L, body("reason", "x")).getResponse().getStatus()).isEqualTo(404);
        var noReason = forceClose(adminPwd, shiftId, body("countedCash", new BigDecimal("500.00")));
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(noReason).has("reason")).isTrue();
        assertThat(forceClose(adminPwd, shiftId, body("countedCash", new BigDecimal("-1"), "reason", "x")).getResponse().getStatus())
                .isEqualTo(400);

        assertThat(forceClose(adminPwd, shiftId, body("reason", "x")).getResponse().getStatus()).isEqualTo(200);
        assertError(forceClose(adminPwd, shiftId, body("reason", "x")), 409, "SHIFT_ALREADY_CLOSED");
    }

    // ---- uncounted: sign-off, late count, Close Day ----

    @Test
    void signOff_ofAnUncountedShift_needsACountOrAConfirmedNoCount() throws Exception {
        var shiftId = uncountedShift();

        var plain = signOff(adminPwd, shiftId, body("note", "ok"));
        assertError(plain, 409, "SHIFT_UNCOUNTED");
        assertThat(body(plain).get("details").get("shiftId").asLong()).isEqualTo(shiftId);

        assertThat(signOff(adminPwd, shiftId, body("confirmNoCount", true)).getResponse().getStatus()).isEqualTo(400);
        assertError(signOff(token(pinLogin(managerPin, null)), shiftId, body("confirmNoCount", true, "note", "Bag lost")),
                403, "REAUTH_REQUIRED");

        var confirmed = signOff(adminPwd, shiftId, body("confirmNoCount", true, "note", "Bag lost"));
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(confirmed).get("signedOff").asBoolean()).isTrue();
        assertThat(cashCount(shiftId)).isEqualTo("NO_COUNT_CONFIRMED");
        assertThat(events(shiftId)).containsExactly("FORCE_CLOSED", "NO_COUNT_CONFIRMED");
    }

    @Test
    void closeDay_blocksAnUncountedShift_untilItsNoCountIsConfirmed() throws Exception {
        var shiftId = uncountedShift();
        var day = LocalDate.ofInstant(clock.instant(), TestLocale.ZONE);

        var before = dayCloseRules.blockers(day).stream().filter(b -> Long.valueOf(shiftId).equals(b.shiftId())).toList();
        assertThat(before).extracting(DayCloseRules.Blocker::code).containsExactly("SHIFT_NOT_SIGNED_OFF");
        assertThat(before.get(0).message()).contains("cash count");

        signOff(adminPwd, shiftId, body("confirmNoCount", true, "note", "Bag lost"));

        assertThat(dayCloseRules.blockers(day)).noneMatch(b -> Long.valueOf(shiftId).equals(b.shiftId()));
    }

    @Test
    void lateCount_recordsTheCount_thenTheShiftSignsOffAsUsual() throws Exception {
        var shiftId = uncountedShift();

        var missing = lateCount(adminPwd, shiftId, body("countedCash", new BigDecimal("490.00")));
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(missing).has("varianceReasonCode")).isTrue();

        var result = lateCount(adminPwd, shiftId, body("countedCash", new BigDecimal("490.00"),
                "varianceReasonCode", "UNRECORDED_TRANSACTION", "note", "Counted next morning"));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(result).get("cashCount").asText()).isEqualTo("COUNTED_LATE");
        assertThat(body(result).get("closingCash").decimalValue()).isEqualByComparingTo("490.00");
        assertThat(body(result).get("variance").decimalValue()).isEqualByComparingTo("-10.00");
        assertThat(body(result).get("varianceAuthorizedById").asLong()).isEqualTo(adminId);
        assertThat(events(shiftId)).containsExactly("FORCE_CLOSED", "LATE_COUNT");

        assertThat(signOff(adminPwd, shiftId, body("note", "ok")).getResponse().getStatus()).isEqualTo(200);
        assertThat(lateCount(adminPwd, shiftId, body("countedCash", new BigDecimal("500.00"))).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void lateCount_ofACountedShift_isRefused() throws Exception {
        var signIn = pinLogin(cashier, till);
        var shiftId = openShift(token(signIn));
        mockMvc.perform(post("/shifts/" + shiftId + "/close").header("Authorization", "Bearer " + token(signIn))
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":500.00}")).andReturn();

        assertError(lateCount(adminPwd, shiftId, body("countedCash", new BigDecimal("500.00"))), 409, "SHIFT_NOT_UNCOUNTED");
    }

    @Test
    void lateCount_needsARecentPasswordSignIn() throws Exception {
        var shiftId = uncountedShift();

        assertError(lateCount(token(pinLogin(managerPin, null)), shiftId, body("countedCash", new BigDecimal("500.00"))),
                403, "REAUTH_REQUIRED");
    }

    @Test
    void aConfirmedNoCount_canStillBeCountedAfterRevokingTheSignOff() throws Exception {
        var shiftId = uncountedShift();
        signOff(adminPwd, shiftId, body("confirmNoCount", true, "note", "Bag lost"));
        assertThat(mockMvc.perform(post("/admin/shifts/" + shiftId + "/signoff/revoke").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Bag found\"}")).andReturn().getResponse().getStatus())
                .isEqualTo(200);

        var result = lateCount(adminPwd, shiftId, body("countedCash", new BigDecimal("500.00")));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("cashCount").asText()).isEqualTo("COUNTED_LATE");
    }

    @Test
    void theAudit_isInsertOnly() throws Exception {
        var shiftId = uncountedShift();

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> jdbc.update("DELETE FROM [shift_cash_count_events] WHERE [shift_id] = ?", shiftId));
        assertThat(jdbc.queryForObject("SELECT [done_by_name] FROM [shift_cash_count_events] WHERE [shift_id] = ?",
                String.class, shiftId)).isNotBlank();
    }

    // ---- helpers ----

    private long uncountedShift() throws Exception {
        var shiftId = openShift(token(pinLogin(cashier, till)));
        assertThat(forceClose(adminPwd, shiftId, body("reason", "Drawer locked away")).getResponse().getStatus()).isEqualTo(200);
        return shiftId;
    }

    private Account createStaff(String role) throws Exception {
        for (int tries = 0; tries < 5; tries++) {
            var pin = String.format("%06d", RANDOM.nextInt(1_000_000));
            var request = new HashMap<String, Object>(Map.of("name", PREFIX + role + " " + RANDOM.nextInt(1_000_000), "role", role, "pin", pin));
            if ("ADMIN".equals(role)) {
                request.put("email", "o2.force." + RANDOM.nextInt(1_000_000_000) + "@example.test");
                request.put("password", "Pw" + Long.toString(Math.abs(RANDOM.nextLong()), 36) + "x9Q");
            }
            var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminPwd)
                    .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(request))).andReturn();
            if (result.getResponse().getStatus() == 201) {
                var id = body(result).get("id").asLong();
                staff.add(id);
                return new Account(id, pin);
            }
        }
        throw new IllegalStateException("Could not create " + role);
    }

    private static Map<String, Object> body(Object... pairs) {
        var map = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private MockHttpServletRequestBuilder on(MockHttpServletRequestBuilder request, Cookie cookie) {
        return cookie == null ? request : request.cookie(cookie);
    }

    private MvcResult pinLogin(Account account, Cookie cookie) throws Exception {
        return mockMvc.perform(on(post("/auth/pin-login"), cookie).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("cashierId", account.id(), "pin", account.pin())))).andReturn();
    }

    private long openShift(String token) throws Exception {
        var result = mockMvc.perform(on(post("/shifts/open"), till).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"openingFloat\":500.00}")).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private MvcResult forceClose(String token, long shiftId, Map<String, Object> request) throws Exception {
        return mockMvc.perform(post("/admin/shifts/" + shiftId + "/force-close").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(request))).andReturn();
    }

    private MvcResult lateCount(String token, long shiftId, Map<String, Object> request) throws Exception {
        return mockMvc.perform(post("/admin/shifts/" + shiftId + "/count").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(request))).andReturn();
    }

    private MvcResult signOff(String token, long shiftId, Map<String, Object> request) throws Exception {
        return mockMvc.perform(post("/admin/shifts/" + shiftId + "/signoff").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(request))).andReturn();
    }

    private MvcResult refresh(MvcResult signIn) throws Exception {
        return mockMvc.perform(post("/auth/refresh").cookie(cookie(signIn, "refreshToken"))).andReturn();
    }

    private String cashCount(long shiftId) {
        return jdbc.queryForObject("SELECT [cash_count] FROM [shifts] WHERE [id] = ?", String.class, shiftId);
    }

    private List<String> events(long shiftId) {
        return jdbc.queryForList("SELECT [action] FROM [shift_cash_count_events] WHERE [shift_id] = ? ORDER BY [id]",
                String.class, shiftId);
    }

    private static Cookie cookie(MvcResult result, String name) {
        for (var header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith(name + "=")) {
                return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
            }
        }
        throw new AssertionError("no " + name + " cookie");
    }

    private static String token(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as("sign-in: " + result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result).get("token").asText();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as("status: " + result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(body(result).get("code").asText()).as("code").isEqualTo(code);
    }

    private static void assertSessionEnded(MvcResult result, String reason) throws Exception {
        assertError(result, 401, "SESSION_ENDED");
        assertThat(body(result).get("details").get("reason").asText()).isEqualTo(reason);
    }
}
