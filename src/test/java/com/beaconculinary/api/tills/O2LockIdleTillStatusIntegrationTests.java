package com.beaconculinary.api.tills;

import com.beaconculinary.api.sessions.SessionService;
import com.beaconculinary.api.shifts.ShiftRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O2 step 6 (R9, R10, R12): GET /pos/till-status, POST /pos/lock and resuming, and idle expiry
 * (the lazy check on refresh and POS writes, and the sweep, called here directly because the suite
 * switches the scheduled job off).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O2LockIdleTillStatusIntegrationTests {
    private static final String PREFIX = "O2 Lock ";
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
    @Autowired
    private SessionService sessionService;

    private final List<Long> staff = new ArrayList<>();
    private String adminPwd;
    private Account owner;
    private Account other;
    private Account manager;
    private Cookie till;
    private String tillCode;
    private Instant start;

    record Account(long id, String pin) {
    }

    @BeforeEach
    void setUp() throws Exception {
        start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.setInstant(start);
        adminPwd = AuthTestHelper.loginAsAdmin(mockMvc);
        owner = createStaff("CASHIER");
        other = createStaff("CASHIER");
        manager = createStaff("ADMIN");
        var result = mockMvc.perform(post("/admin/devices").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", PREFIX + RANDOM.nextInt(1_000_000),
                        "serialNumber", "O2L" + Long.toString(Math.abs(RANDOM.nextLong()), 36).toUpperCase())))).andReturn();
        assertThat(result.getResponse().getStatus()).as("register till").isEqualTo(201);
        till = cookie(result, "deviceToken");
        tillCode = body(result).get("tillCode").asText();
    }

    @AfterEach
    void tearDown() {
        shiftRepository.deleteAll();
        for (var id : staff) {
            jdbc.update("DELETE FROM [user_sessions] WHERE [user_id] = ?", id);
        }
        jdbc.update("DELETE c FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id] WHERE d.[name] LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM [devices] WHERE [name] LIKE ?", PREFIX + "%");
        TestStaffCleanup.delete(jdbc, staff);
        staff.clear();
        clock.reset();
    }

    // ---- till status ----

    @Test
    void tillStatus_onAnUnregisteredBrowser_showsNoTillAndNoShift() throws Exception {
        var ownerOnNullTill = pinLogin(owner, null);
        openShift(token(ownerOnNullTill), null);

        var status = tillStatus(null);

        assertThat(status.get("registered").asBoolean()).isFalse();
        assertThat(status.get("tillCode").isNull()).isTrue();
        assertThat(status.get("tillName").isNull()).isTrue();
        assertThat(status.get("shift").isNull()).isTrue();
        assertThat(status.get("idleLockMinutes").asInt()).isEqualTo(5);
        assertThat(status.get("requireRegistered").asBoolean()).isFalse();
    }

    @Test
    void tillStatus_onARegisteredTill_withoutAShift() throws Exception {
        var status = tillStatus(till);

        assertThat(status.get("registered").asBoolean()).isTrue();
        assertThat(status.get("tillCode").asText()).isEqualTo(tillCode);
        assertThat(status.get("tillName").asText()).startsWith(PREFIX);
        assertThat(status.get("shift").isNull()).isTrue();
    }

    @Test
    void tillStatus_needsNoSignIn() throws Exception {
        assertThat(mockMvc.perform(get("/pos/till-status")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    // ---- lock and resume ----

    @Test
    void lock_endsTheSession_keepsTheShiftOpen_andShowsTheTillLocked() throws Exception {
        var signIn = pinLogin(owner, till);
        var shiftId = body(openShift(token(signIn), till)).get("id").asLong();
        var before = tillStatus(till).get("shift");
        assertThat(before.get("shiftId").asLong()).isEqualTo(shiftId);
        assertThat(before.get("ownerId").asLong()).isEqualTo(owner.id());
        assertThat(before.get("ownerName").asText()).startsWith(PREFIX);
        assertThat(before.get("openedAt").asText()).isNotBlank();
        assertThat(before.get("locked").asBoolean()).isFalse();

        var lock = lock(token(signIn));

        assertThat(lock.getResponse().getStatus()).isEqualTo(204);
        assertThat(lock.getResponse().getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith("refreshToken=;") && h.contains("Max-Age=0"));
        assertThat(tillStatus(till).get("shift").get("locked").asBoolean()).isTrue();
        assertSessionEnded(refresh(signIn), "LOCK");
        assertSessionEnded(closeShift(token(signIn), shiftId), "LOCK");
        assertThat(jdbc.queryForObject("SELECT [status] FROM [shifts] WHERE [id] = ?", String.class, shiftId)).isEqualTo("OPEN");
    }

    @Test
    void lock_isIdempotent() throws Exception {
        var signIn = pinLogin(owner, till);

        assertThat(lock(token(signIn)).getResponse().getStatus()).isEqualTo(204);
        assertThat(lock(token(signIn)).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void lock_needsASignIn() throws Exception {
        assertThat(mockMvc.perform(post("/pos/lock")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    @Test
    void afterALock_theOwnerResumes_othersGetTillLocked_andAnAdminCanOverride() throws Exception {
        var first = pinLogin(owner, till);
        var shiftId = body(openShift(token(first), till)).get("id").asLong();
        lock(token(first));

        var refused = pinLogin(other, till);
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(refused).get("code").asText()).isEqualTo("TILL_LOCKED");
        assertThat(body(refused).get("details").get("tillCode").asText()).isEqualTo(tillCode);

        var resumed = pinLogin(owner, till);
        assertThat(resumed.getResponse().getStatus()).isEqualTo(200);
        assertThat(tillStatus(till).get("shift").get("locked").asBoolean()).isFalse();
        lock(token(resumed));

        var admin = pinLogin(manager, till);
        assertThat(admin.getResponse().getStatus()).isEqualTo(200);
        assertThat(closeShift(token(admin), shiftId).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- idle (R10) ----

    @Test
    void aPosSessionIdleBeyondTheExpiry_isRefused_asIdle() throws Exception {
        var signIn = pinLogin(owner, till);
        clock.setInstant(start.plus(Duration.ofMinutes(31)));

        assertSessionEnded(openShift(token(signIn), till), "IDLE");
        assertSessionEnded(refresh(signIn), "IDLE");
    }

    @Test
    void anIdleOwner_showsTheTillAsLocked() throws Exception {
        var signIn = pinLogin(owner, till);
        openShift(token(signIn), till);
        clock.setInstant(start.plus(Duration.ofMinutes(31)));

        assertThat(tillStatus(till).get("shift").get("locked").asBoolean()).isTrue();
    }

    @Test
    void justUnderTheExpiry_theSessionStillWorks() throws Exception {
        var signIn = pinLogin(owner, till);
        clock.setInstant(start.plus(Duration.ofMinutes(29)));

        assertThat(openShift(token(signIn), till).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void aPosWrite_countsAsActivity() throws Exception {
        var signIn = pinLogin(owner, till);
        clock.setInstant(start.plus(Duration.ofMinutes(20)));
        assertThat(openShift(token(signIn), till).getResponse().getStatus()).isEqualTo(201);

        clock.setInstant(start.plus(Duration.ofMinutes(45)));

        assertThat(refresh(signIn).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void refreshing_doesntCountAsActivity() throws Exception {
        var signIn = pinLogin(owner, till);
        clock.setInstant(start.plus(Duration.ofMinutes(20)));
        assertThat(refresh(signIn).getResponse().getStatus()).isEqualTo(200);

        clock.setInstant(start.plus(Duration.ofMinutes(31)));

        assertSessionEnded(refresh(signIn), "IDLE");
    }

    @Test
    void backOfficeSessions_areNeverIdledOut() throws Exception {
        var signIn = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("email", AuthTestHelper.ADMIN_EMAIL, "password", "654321")))).andReturn();
        clock.setInstant(start.plus(Duration.ofHours(3)));

        sessionService.sweep();

        assertThat(refresh(signIn).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theSweep_recordsIdleAndExpiredSessions() throws Exception {
        var pos = pinLogin(owner, till);
        var office = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("email", AuthTestHelper.ADMIN_EMAIL, "password", "654321")))).andReturn();

        clock.setInstant(start.plus(Duration.ofMinutes(31)));
        sessionService.sweep();
        assertThat(endReason(token(pos))).isEqualTo("IDLE");
        assertThat(endReason(token(office))).isNull();

        clock.setInstant(start.plus(Duration.ofDays(8)));
        sessionService.sweep();
        assertThat(endReason(token(office))).isEqualTo("EXPIRED");
        assertThat(endReason(token(pos))).as("the first reason stays").isEqualTo("IDLE");
    }

    // ---- helpers ----

    private Account createStaff(String role) throws Exception {
        for (int tries = 0; tries < 5; tries++) {
            var pin = String.format("%06d", RANDOM.nextInt(1_000_000));
            var body = new HashMap<String, Object>(Map.of("name", PREFIX + role + " " + RANDOM.nextInt(1_000_000), "role", role, "pin", pin));
            if ("ADMIN".equals(role)) {
                body.put("email", "o2.lock." + RANDOM.nextInt(1_000_000_000) + "@example.test");
                body.put("password", "Pw" + Long.toString(Math.abs(RANDOM.nextLong()), 36) + "x9Q");
            }
            var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminPwd)
                    .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body))).andReturn();
            if (result.getResponse().getStatus() == 201) {
                var id = body(result).get("id").asLong();
                staff.add(id);
                return new Account(id, pin);
            }
        }
        throw new IllegalStateException("Could not create " + role);
    }

    private MockHttpServletRequestBuilder on(MockHttpServletRequestBuilder request, Cookie cookie) {
        return cookie == null ? request : request.cookie(cookie);
    }

    private MvcResult pinLogin(Account account, Cookie cookie) throws Exception {
        return mockMvc.perform(on(post("/auth/pin-login"), cookie).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("cashierId", account.id(), "pin", account.pin())))).andReturn();
    }

    private JsonNode tillStatus(Cookie cookie) throws Exception {
        var result = mockMvc.perform(on(get("/pos/till-status"), cookie)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return body(result);
    }

    private MvcResult lock(String token) throws Exception {
        return mockMvc.perform(post("/pos/lock").header("Authorization", "Bearer " + token)).andReturn();
    }

    private MvcResult refresh(MvcResult signIn) throws Exception {
        return mockMvc.perform(post("/auth/refresh").cookie(cookie(signIn, "refreshToken"))).andReturn();
    }

    private MvcResult openShift(String token, Cookie cookie) throws Exception {
        return mockMvc.perform(on(post("/shifts/open"), cookie).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openingFloat", new BigDecimal("500.00"))))).andReturn();
    }

    private MvcResult closeShift(String token, long shiftId) throws Exception {
        return mockMvc.perform(post("/shifts/" + shiftId + "/close").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":500.00}")).andReturn();
    }

    private String endReason(String accessToken) throws Exception {
        var sid = MAPPER.readTree(new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8))
                .get("sid").asText();
        return jdbc.queryForObject("SELECT [end_reason] FROM [user_sessions] WHERE [id] = ?", String.class, sid);
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

    private static void assertSessionEnded(MvcResult result, String reason) throws Exception {
        assertThat(result.getResponse().getStatus()).as("status: " + result.getResponse().getContentAsString()).isEqualTo(401);
        assertThat(body(result).get("code").asText()).isEqualTo("SESSION_ENDED");
        assertThat(body(result).get("details").get("reason").asText()).isEqualTo(reason);
    }
}
