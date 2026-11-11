package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.support.TestStaffCleanup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Requests and staff set-up shared by the WS1-1 Phase 2 tests. Credentials are generated at run
 * time and never written down; every staff member created here is deleted by {@link #cleanUp()}.
 */
class LoginProtectionTestClient {
    static final SecureRandom RANDOM = new SecureRandom();
    static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbc;
    private final String namePrefix;
    private final List<Long> createdUserIds = new ArrayList<>();
    /** A fresh address per client (per test), so tests sharing a Spring context don't share a burst bucket. */
    private final String defaultIp = "203.0." + RANDOM.nextInt(256) + "." + (1 + RANDOM.nextInt(254));
    String adminToken;

    LoginProtectionTestClient(MockMvc mockMvc, JdbcTemplate jdbc, String namePrefix) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
        this.namePrefix = namePrefix;
    }

    void cleanUp() {
        TestStaffCleanup.delete(jdbc, createdUserIds);
        createdUserIds.clear();
    }

    // ---- fail-open guard: protection errors are swallowed in production, so tests must look ----

    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> errorCapture;

    void captureProtectionErrors() {
        errorCapture = new ch.qos.logback.core.read.ListAppender<>();
        errorCapture.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LoginAttemptService.class)).addAppender(errorCapture);
    }

    /** Fails if protection logged an ERROR (it would have failed open and silently not protected). */
    void assertNoProtectionErrors() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LoginAttemptService.class)).detachAppender(errorCapture);
        assertThat(errorCapture.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .map(e -> e.getFormattedMessage() + (e.getThrowableProxy() == null ? "" : " / " + e.getThrowableProxy().getMessage()))
                .toList())
                .as("login protection errors (fail-open) during the test")
                .isEmpty();
    }

    void clearProtectionTables() {
        jdbc.update("DELETE FROM [login_attempts]");
        jdbc.update("DELETE FROM [user_auth_state]");
    }

    // ---- staff ----

    long createStaff(Map<String, Object> fields) throws Exception {
        var body = new HashMap<>(fields);
        body.put("name", namePrefix + " " + RANDOM.nextInt(1_000_000));
        var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn();
        assertThat(result.getResponse().getStatus()).as("create staff").isEqualTo(201);
        var id = MAPPER.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        createdUserIds.add(id);
        return id;
    }

    /** A password user (STOCK_CLERK); returns {id, email, password}. */
    Account passwordUser() throws Exception {
        var email = namePrefix.toLowerCase().replace(' ', '.') + "." + RANDOM.nextInt(1_000_000_000) + "@example.test";
        var password = randomPassword();
        return new Account(createStaff(Map.of("role", "STOCK_CLERK", "email", email, "password", password)), email, password, null);
    }

    /** A cashier with a PIN; returns {id, null, null, pin}. */
    Account cashier() throws Exception {
        var pin = randomPin();
        return new Account(createStaff(Map.of("role", "CASHIER", "pin", pin)), null, null, pin);
    }

    /** A Head Chef with both a password and a PIN. */
    Account chefWithPin() throws Exception {
        var email = namePrefix.toLowerCase().replace(' ', '.') + ".chef." + RANDOM.nextInt(1_000_000_000) + "@example.test";
        var password = randomPassword();
        var pin = randomPin();
        var id = createStaff(Map.of("role", "HEAD_CHEF", "email", email, "password", password, "pin", pin));
        return new Account(id, email, password, pin);
    }

    /** A new admin whose PIN no other admin uses; returns the PIN. */
    String adminPin() throws Exception {
        for (int tries = 0; tries < 5; tries++) {
            var pin = randomPin();
            var body = Map.<String, Object>of("name", namePrefix + " Admin", "role", "ADMIN",
                    "email", "ws1.admin." + RANDOM.nextInt(1_000_000_000) + "@example.test",
                    "password", randomPassword(), "pin", pin);
            var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn();
            if (result.getResponse().getStatus() == 201) {
                createdUserIds.add(MAPPER.readTree(result.getResponse().getContentAsString()).get("id").asLong());
                return pin;
            }
        }
        throw new IllegalStateException("Could not create an admin with a unique PIN");
    }

    record Account(long id, String email, String password, String pin) {
    }

    // ---- requests ----

    MvcResult passwordLogin(String email, String password) throws Exception {
        return passwordLogin(email, password, defaultIp);
    }

    MvcResult passwordLogin(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("Origin", "http://localhost:3000")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .content(json(Map.of("email", email, "password", password)))).andReturn();
    }

    MvcResult pinLogin(long cashierId, String pin, String ip) throws Exception {
        return mockMvc.perform(post("/auth/pin-login").contentType(MediaType.APPLICATION_JSON)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .content(json(Map.of("cashierId", cashierId, "pin", pin)))).andReturn();
    }

    MvcResult authorize(String token, String pin) throws Exception {
        return mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("pin", pin)))).andReturn();
    }

    MvcResult changePassword(String token, long userId, String oldPassword, String newPassword) throws Exception {
        return mockMvc.perform(post("/users/" + userId + "/change-password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("oldPassword", oldPassword, "newPassword", newPassword)))).andReturn();
    }

    MvcResult refresh(Cookie refreshCookie) throws Exception {
        return mockMvc.perform(post("/auth/refresh").cookie(refreshCookie)).andReturn();
    }

    JsonNode staff(long id) throws Exception {
        var result = mockMvc.perform(get("/admin/staff/" + id).header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    MvcResult unlock(long id, String scope) throws Exception {
        var request = post("/admin/staff/" + id + "/unlock").header("Authorization", "Bearer " + adminToken);
        if (scope != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json(Map.of("scope", scope)));
        }
        return mockMvc.perform(request).andReturn();
    }

    MvcResult setPin(long id, String pin) throws Exception {
        return mockMvc.perform(post("/admin/staff/" + id + "/pin").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("pin", pin)))).andReturn();
    }

    MvcResult setPassword(long id, String password) throws Exception {
        return mockMvc.perform(post("/admin/staff/" + id + "/password").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("password", password)))).andReturn();
    }

    // ---- assertions ----

    static String token(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as("sign-in status").isEqualTo(200);
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    /** A 429 with the given code and retryAfterSeconds (null: no Retry-After header, "ask a manager"). */
    static void assertRefused(MvcResult result, String code, Long retryAfterSeconds) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        var body = body(result);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.has("message")).isTrue();
        if (retryAfterSeconds == null) {
            assertThat(body.get("retryAfterSeconds").isNull()).as("retryAfterSeconds is null").isTrue();
            assertThat(result.getResponse().getHeader("Retry-After")).isNull();
            assertThat(body.get("message").asText()).isEqualTo("Too many failed attempts. Ask a manager to unlock sign-in.");
        } else {
            assertThat(body.get("retryAfterSeconds").asLong()).isEqualTo(retryAfterSeconds);
            assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo(String.valueOf(retryAfterSeconds));
        }
    }

    static void assertUnauthorizedEmpty(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
    }

    static String json(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    static String randomPin() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** A 6-digit PIN that differs from {@code pin}. */
    static String wrongPin(String pin) {
        var wrong = randomPin();
        return wrong.equals(pin) ? String.format("%06d", (Integer.parseInt(pin) + 1) % 1_000_000) : wrong;
    }

    static String randomPassword() {
        var alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var builder = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return builder.toString();
    }
}
