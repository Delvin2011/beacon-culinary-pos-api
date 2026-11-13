package com.beaconculinary.api.devices;

import com.beaconculinary.api.support.TestStaffCleanup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Requests and set-up shared by the WS1-1b device tests. Credentials and device tokens are
 * generated at run time and never printed: assertions on them compare booleans, not values.
 * Every staff member and till created here is removed by {@link #cleanUp()}.
 */
class DeviceTestClient {
    static final String PREFIX = "WS1b ";
    static final SecureRandom RANDOM = new SecureRandom();
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbc;
    private final List<Long> createdUserIds = new ArrayList<>();
    /** A fresh address per client, so tests sharing a Spring context don't share a burst bucket. */
    final String ip = "198.51." + RANDOM.nextInt(256) + "." + (1 + RANDOM.nextInt(254));
    String adminToken;

    DeviceTestClient(MockMvc mockMvc, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
    }

    /** Removes every till named with the test prefix (and its browsers), and the staff created here. */
    static void deleteTestDevices(JdbcTemplate jdbc) {
        jdbc.update("DELETE c FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id] WHERE d.[name] LIKE 'WS1b %'");
        jdbc.update("DELETE FROM [devices] WHERE [name] LIKE 'WS1b %'");
    }

    void cleanUp() {
        deleteTestDevices(jdbc);
        TestStaffCleanup.delete(jdbc, createdUserIds);
        createdUserIds.clear();
    }

    void clearProtectionTables() {
        jdbc.update("DELETE FROM [login_attempts]");
        jdbc.update("DELETE FROM [user_auth_state]");
    }

    // ---- tills ----

    static String serial() {
        return "WS1B" + Long.toString(Math.abs(RANDOM.nextLong()), 36).toUpperCase();
    }

    /** POST /admin/devices from a browser without a till cookie (or with {@code cookie}). */
    MvcResult register(String serial, String assetTag, String joinDeviceId, Cookie cookie) throws Exception {
        return register(adminToken, serial, assetTag, joinDeviceId, cookie);
    }

    MvcResult register(String token, String serial, String assetTag, String joinDeviceId, Cookie cookie) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("name", PREFIX + "Till " + RANDOM.nextInt(1_000_000));
        if (serial != null) body.put("serialNumber", serial);
        if (assetTag != null) body.put("assetTag", assetTag);
        if (joinDeviceId != null) body.put("joinDeviceId", joinDeviceId);
        var request = post("/admin/devices").header("Authorization", "Bearer " + token)
                .header("User-Agent", CHROME_WINDOWS)
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
        return mockMvc.perform(withTill(request, cookie)).andReturn();
    }

    /** Registers a new till from a fresh browser; returns its cookie and device id. */
    Till newTill() throws Exception {
        return newTillWithSerial(serial());
    }

    Till newTillWithSerial(String serial) throws Exception {
        var result = register(serial, null, null, null);
        assertThat(result.getResponse().getStatus()).as("register till").isEqualTo(201);
        return new Till(body(result).get("id").asText(), deviceCookie(result));
    }

    record Till(String deviceId, Cookie cookie) {
    }

    MvcResult list(Cookie cookie) throws Exception {
        return mockMvc.perform(withTill(get("/admin/devices").header("Authorization", "Bearer " + adminToken), cookie)).andReturn();
    }

    JsonNode device(String id) throws Exception {
        for (var node : body(list(null))) {
            if (node.get("id").asText().equals(id)) {
                return node;
            }
        }
        throw new AssertionError("device not listed");
    }

    MvcResult revokeDevice(String id) throws Exception {
        return mockMvc.perform(post("/admin/devices/" + id + "/revoke").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "test")))).andReturn();
    }

    MvcResult revokeCredential(String id, String credentialId) throws Exception {
        return mockMvc.perform(post("/admin/devices/" + id + "/credentials/" + credentialId + "/revoke")
                .header("Authorization", "Bearer " + adminToken)).andReturn();
    }

    MvcResult update(String id, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put("/admin/devices/" + id).header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn();
    }

    // ---- staff and sign-in ----

    long createStaff(Map<String, Object> fields) throws Exception {
        var body = new HashMap<>(fields);
        body.put("name", PREFIX + RANDOM.nextInt(1_000_000));
        var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn();
        assertThat(result.getResponse().getStatus()).as("create staff").isEqualTo(201);
        var id = body(result).get("id").asLong();
        createdUserIds.add(id);
        return id;
    }

    record Account(long id, String pin) {
    }

    Account cashier() throws Exception {
        var pin = randomPin();
        return new Account(createStaff(Map.of("role", "CASHIER", "pin", pin)), pin);
    }

    Account headChef() throws Exception {
        var pin = randomPin();
        return new Account(createStaff(Map.of("role", "HEAD_CHEF", "pin", pin,
                "email", "ws1b.chef." + RANDOM.nextInt(1_000_000_000) + "@example.test", "password", randomPassword())), pin);
    }

    /** A new admin whose PIN no other admin uses. */
    Account admin() throws Exception {
        for (int tries = 0; tries < 5; tries++) {
            var pin = randomPin();
            var body = Map.<String, Object>of("name", PREFIX + "Admin", "role", "ADMIN",
                    "email", "ws1b.admin." + RANDOM.nextInt(1_000_000_000) + "@example.test",
                    "password", randomPassword(), "pin", pin);
            var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn();
            if (result.getResponse().getStatus() == 201) {
                var id = body(result).get("id").asLong();
                createdUserIds.add(id);
                return new Account(id, pin);
            }
        }
        throw new IllegalStateException("Could not create an admin with a unique PIN");
    }

    MvcResult pinLogin(long userId, String pin, Cookie cookie) throws Exception {
        return mockMvc.perform(withTill(post("/auth/pin-login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("cashierId", userId, "pin", pin))), cookie)).andReturn();
    }

    MvcResult authorize(String token, String pin, Cookie cookie) throws Exception {
        return mockMvc.perform(withTill(post("/admin/authorize").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("pin", pin))), cookie)).andReturn();
    }

    MvcResult tiles(String screen, Cookie cookie) throws Exception {
        return mockMvc.perform(withTill(get("/users/login-tiles").param("screen", screen), cookie)).andReturn();
    }

    MvcResult cashiers(Cookie cookie) throws Exception {
        return mockMvc.perform(withTill(get("/users/cashiers"), cookie)).andReturn();
    }

    MvcResult anyRequest(Cookie cookie) throws Exception {
        return cashiers(cookie);
    }

    private MockHttpServletRequestBuilder withTill(MockHttpServletRequestBuilder request, Cookie cookie) {
        request = request.with((MockHttpServletRequest r) -> {
            r.setRemoteAddr(ip);
            return r;
        });
        return cookie == null ? request : request.cookie(cookie);
    }

    // ---- helpers ----

    /** The deviceToken cookie set by a response, or null. Its value is never printed. */
    static Cookie deviceCookie(MvcResult result) {
        for (var header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith(DeviceCookies.NAME + "=")) {
                var value = header.substring(DeviceCookies.NAME.length() + 1, header.indexOf(';'));
                return new Cookie(DeviceCookies.NAME, value);
            }
        }
        return null;
    }

    static String setCookieHeader(MvcResult result) {
        for (var header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith(DeviceCookies.NAME + "=")) {
                return header;
            }
        }
        return null;
    }

    /** The claims of a JWT, decoded without verification (the server already verified it). */
    static JsonNode claims(String jwt) throws Exception {
        var payload = jwt.split("\\.")[1];
        return MAPPER.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    static String token(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as("sign-in status").isEqualTo(200);
        return body(result).get("token").asText();
    }

    static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as("status").isEqualTo(status);
        assertThat(body(result).get("code").asText()).as("code").isEqualTo(code);
    }

    static String json(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    static String randomPin() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

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
