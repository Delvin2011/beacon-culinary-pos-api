package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.support.AuthTestHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WS1-1 Phase 1 in monitor mode: every attempt on the four paths is recorded with its outcome, IP
 * fields and would_block flag, and what the caller sees is exactly what it was before.
 *
 * <p>Uses staff created here with random credentials (generated at run time, never written down),
 * so the seed accounts' counters are untouched. Both protection tables are emptied before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "security.login-protection.mode=monitor")
class LoginProtectionMonitorIntegrationTests {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private LoginProtectionProperties properties;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        assertThat(properties.getMode()).isEqualTo(LoginProtectionMode.MONITOR);
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        jdbc.update("DELETE FROM [login_attempts]");
        jdbc.update("DELETE FROM [user_auth_state]");
    }

    // ---- PASSWORD ----

    @Test
    void passwordLogin_unknownEmail_isTrackedByEmail_andTheSixthAttemptWouldBeBlocked() throws Exception {
        var email = "Nobody." + RANDOM.nextInt(1_000_000) + "@Example.test";
        for (int i = 0; i < 6; i++) {
            var result = passwordLogin(email, randomPassword(), "203.0.113.10");
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }

        var rows = rows("PASSWORD", email.toLowerCase());
        assertThat(rows).hasSize(6);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("outcome")).isEqualTo("UNKNOWN_USER");
            assertThat(row.get("user_id")).isNull();
            assertThat(row.get("client_ip")).isEqualTo("203.0.113.10");
            assertThat(row.get("remote_addr")).isEqualTo("203.0.113.10");
        });
        assertWouldBlockOnlyFrom(rows, 5, "PASSWORD_ACCOUNT");
    }

    @Test
    void passwordLogin_knownUser_responsesUnchanged_andASuccessResetsTheCount() throws Exception {
        var email = "ws1.clerk." + RANDOM.nextInt(1_000_000) + "@example.test";
        var password = randomPassword();
        var userId = createStaff(Map.of("role", "STOCK_CLERK", "email", email, "password", password));

        for (int i = 0; i < 5; i++) {
            var wrong = passwordLogin(email, randomPassword(), "203.0.113.11");
            assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
            assertThat(wrong.getResponse().getContentAsString()).isEmpty();
        }
        var success = passwordLogin(email, password, "203.0.113.11");
        assertThat(success.getResponse().getStatus()).isEqualTo(200);
        assertThat(success.getResponse().getContentAsString()).contains("\"token\"");
        var afterReset = passwordLogin(email, randomPassword(), "203.0.113.11");
        assertThat(afterReset.getResponse().getStatus()).isEqualTo(401);

        var rows = rows("PASSWORD", email);
        assertThat(rows).extracting(r -> r.get("outcome")).containsExactly(
                "BAD_CREDENTIAL", "BAD_CREDENTIAL", "BAD_CREDENTIAL", "BAD_CREDENTIAL", "BAD_CREDENTIAL", "SUCCESS", "BAD_CREDENTIAL");
        assertThat(rows).allSatisfy(row -> assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userId));
        // The 6th attempt would have been blocked, yet still succeeded; the success then reset the count.
        assertThat(rows).extracting(r -> r.get("would_block")).containsExactly(false, false, false, false, false, true, false);
        assertThat(rows.get(5).get("rule_triggered")).isEqualTo("PASSWORD_ACCOUNT");
        assertThat(lastSuccess(userId, "PASSWORD")).isNotNull();
    }

    // ---- PIN ----

    @Test
    void pinLogin_pairRuleAtSixFromOneIp_thenAccountCeilingAtTwentyOneFromAnySource() throws Exception {
        var cashierId = createStaff(Map.of("role", "CASHIER", "pin", randomPin()));

        for (int i = 0; i < 6; i++) {
            var result = pinLogin(cashierId, wrongPin(), "10.0.0.1");
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }
        var pairRows = rows("PIN", String.valueOf(cashierId));
        assertWouldBlockOnlyFrom(pairRows, 5, "PIN_PAIR");

        // 6 failures so far. 5 + 5 + 4 more from other addresses reach 20 without tripping any pair.
        for (var ip : List.of("10.0.0.2", "10.0.0.2", "10.0.0.2", "10.0.0.2", "10.0.0.2",
                "10.0.0.3", "10.0.0.3", "10.0.0.3", "10.0.0.3", "10.0.0.3",
                "10.0.0.4", "10.0.0.4", "10.0.0.4", "10.0.0.4", "10.0.0.5")) {
            assertThat(pinLogin(cashierId, wrongPin(), ip).getResponse().getStatus()).isEqualTo(401);
        }

        var rows = rows("PIN", String.valueOf(cashierId));
        assertThat(rows).hasSize(21);
        assertThat(rows.subList(6, 20)).allSatisfy(row -> assertThat(row.get("would_block")).isEqualTo(false));
        assertThat(rows.get(20).get("would_block")).isEqualTo(true);
        assertThat(rows.get(20).get("rule_triggered")).isEqualTo("PIN_ACCOUNT_CEILING");
        assertThat(rows.get(20).get("client_ip")).isEqualTo("10.0.0.5");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("outcome")).isEqualTo("BAD_CREDENTIAL");
            assertThat(row.get("pin_non_standard")).isNull();
        });
    }

    @Test
    void pinLogin_unknownCashier_isRecordedWithoutAUser() throws Exception {
        var unknownId = 900_000_000L + RANDOM.nextInt(1_000_000);
        var result = pinLogin(unknownId, wrongPin(), "10.0.1.1");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);

        var rows = rows("PIN", String.valueOf(unknownId));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("UNKNOWN_USER");
            assertThat(row.get("user_id")).isNull();
        });
    }

    @Test
    void pinLogin_success_setsPinNonStandard_onlyWhenThePinIsNotSixDigits() throws Exception {
        var pin = randomPin();
        var cashierId = createStaff(Map.of("role", "CASHIER", "pin", pin));
        assertThat(pinLogin(cashierId, pin, "10.0.2.1").getResponse().getStatus()).isEqualTo(200);

        // A legacy 4-digit PIN can't be set through staff management, so set its hash directly.
        var shortPin = String.format("%04d", RANDOM.nextInt(10_000));
        jdbc.update("UPDATE [users] SET [pin_hash] = ? WHERE [id] = ?", passwordEncoder.encode(shortPin), cashierId);
        assertThat(pinLogin(cashierId, shortPin, "10.0.2.1").getResponse().getStatus()).isEqualTo(200);

        var rows = rows("PIN", String.valueOf(cashierId));
        assertThat(rows).extracting(r -> r.get("outcome")).containsExactly("SUCCESS", "SUCCESS");
        assertThat(rows).extracting(r -> r.get("pin_non_standard")).containsExactly(false, true);
        assertThat(lastSuccess(cashierId, "PIN")).isNotNull();
    }

    @Test
    void pinLogin_validationFailure_isRecorded_andTheBodyIsUnchanged() throws Exception {
        var cashierId = 900_000_000L + RANDOM.nextInt(1_000_000);
        var result = mockMvc.perform(post("/auth/pin-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cashierId\":" + cashierId + ",\"pin\":\"12\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(MAPPER.readTree(result.getResponse().getContentAsString()).get("pin").asText()).isEqualTo("PIN must be 4-6 digits");

        assertThat(rows("PIN", String.valueOf(cashierId))).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("VALIDATION");
            assertThat(row.get("would_block")).isEqualTo(false);
        });
    }

    // ---- MANAGER_PIN ----

    @Test
    void managerPin_callerRuleAtSix_responsesUnchanged_andASuccessResets() throws Exception {
        var adminPin = createAdminWithUniquePin();
        var caller = newCashierSession();

        for (int i = 0; i < 6; i++) {
            var wrong = authorize(caller.token(), wrongPin());
            assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
            assertThat(MAPPER.readTree(wrong.getResponse().getContentAsString()).get("error").asText()).isEqualTo("Invalid PIN.");
        }
        assertThat(authorize(caller.token(), adminPin).getResponse().getStatus()).isEqualTo(200);
        assertThat(authorize(caller.token(), wrongPin()).getResponse().getStatus()).isEqualTo(401);

        var rows = rows("MANAGER_PIN", String.valueOf(caller.id()));
        assertThat(rows).hasSize(8);
        assertThat(rows).allSatisfy(row -> assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(caller.id()));
        assertThat(rows).extracting(r -> r.get("would_block")).containsExactly(false, false, false, false, false, true, true, false);
        assertThat(rows.get(5).get("rule_triggered")).isEqualTo("MANAGER_PIN_CALLER");
        assertThat(rows.get(6).get("outcome")).isEqualTo("SUCCESS");
    }

    @Test
    void managerPin_globalCeilingAcrossCallers_alertsWithoutBlocking() throws Exception {
        for (int c = 0; c < 5; c++) {
            var caller = newCashierSession();
            for (int i = 0; i < 4; i++) {
                assertThat(authorize(caller.token(), wrongPin()).getResponse().getStatus()).isEqualTo(401);
            }
        }
        var sixth = newCashierSession();
        assertThat(authorize(sixth.token(), wrongPin()).getResponse().getStatus()).isEqualTo(401);

        var row = rows("MANAGER_PIN", String.valueOf(sixth.id())).get(0);
        assertThat(row.get("rule_triggered")).isEqualTo("MANAGER_PIN_GLOBAL");
        assertThat(row.get("would_block")).isEqualTo(false);
    }

    // ---- CHANGE_PASSWORD ----

    @Test
    void changePassword_ruleAtSix_responsesUnchanged_andSuccessRecorded() throws Exception {
        var email = "ws1.changer." + RANDOM.nextInt(1_000_000) + "@example.test";
        var password = randomPassword();
        var userId = createStaff(Map.of("role", "STOCK_CLERK", "email", email, "password", password));
        var token = token(passwordLogin(email, password, "203.0.113.20"));

        for (int i = 0; i < 6; i++) {
            var wrong = changePassword(token, userId, randomPassword(), randomPassword());
            assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
            assertThat(wrong.getResponse().getContentAsString()).isEmpty();
        }
        assertThat(changePassword(token, userId, password, randomPassword()).getResponse().getStatus()).isEqualTo(200);

        var rows = rows("CHANGE_PASSWORD", String.valueOf(userId));
        assertThat(rows).extracting(r -> r.get("outcome")).containsExactly(
                "BAD_CREDENTIAL", "BAD_CREDENTIAL", "BAD_CREDENTIAL", "BAD_CREDENTIAL", "BAD_CREDENTIAL", "BAD_CREDENTIAL", "SUCCESS");
        assertThat(rows.get(5).get("would_block")).isEqualTo(true);
        assertThat(rows.get(5).get("rule_triggered")).isEqualTo("CHANGE_PASSWORD_USER");
        assertThat(rows.get(6).get("would_block")).isEqualTo(true);
    }

    // ---- network fields ----

    @Test
    void forwardedForAndUserAgent_areRecordedRaw_butNotTrustedWithoutConfiguredProxies() throws Exception {
        var email = "nobody.xff." + RANDOM.nextInt(1_000_000) + "@example.test";
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "6.6.6.6, 198.51.100.4")
                .header("User-Agent", "POS-Till/1.0")
                .with(request -> {
                    request.setRemoteAddr("10.20.30.40");
                    return request;
                })
                .content(json(Map.of("email", email, "password", randomPassword()))));

        assertThat(rows("PASSWORD", email)).singleElement().satisfies(row -> {
            assertThat(row.get("remote_addr")).isEqualTo("10.20.30.40");
            assertThat(row.get("client_ip")).isEqualTo("10.20.30.40");
            assertThat(row.get("forwarded_for")).isEqualTo("6.6.6.6, 198.51.100.4");
            assertThat(row.get("user_agent")).isEqualTo("POS-Till/1.0");
            assertThat(row.get("device_id")).isNull();
        });
    }

    // ---- helpers ----

    private record Session(long id, String token) {
    }

    private void assertWouldBlockOnlyFrom(List<Map<String, Object>> rows, int firstBlockedIndex, String rule) {
        for (int i = 0; i < rows.size(); i++) {
            var blocked = i >= firstBlockedIndex;
            assertThat(rows.get(i).get("would_block")).as("would_block of attempt %d", i + 1).isEqualTo(blocked);
            assertThat(rows.get(i).get("rule_triggered")).as("rule of attempt %d", i + 1).isEqualTo(blocked ? rule : null);
        }
    }

    private List<Map<String, Object>> rows(String method, String identifier) {
        return jdbc.queryForList("SELECT * FROM [login_attempts] WHERE [method] = ? AND [identifier] = ? ORDER BY [id]",
                method, identifier);
    }

    private Object lastSuccess(long userId, String scope) {
        var rows = jdbc.queryForList("SELECT [last_success_at] FROM [user_auth_state] WHERE [user_id] = ? AND [scope] = ?",
                userId, scope);
        return rows.isEmpty() ? null : rows.get(0).get("last_success_at");
    }

    private MvcResult passwordLogin(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .content(json(Map.of("email", email, "password", password)))).andReturn();
    }

    private MvcResult pinLogin(long cashierId, String pin, String ip) throws Exception {
        return mockMvc.perform(post("/auth/pin-login").contentType(MediaType.APPLICATION_JSON)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .content(json(Map.of("cashierId", cashierId, "pin", pin)))).andReturn();
    }

    private MvcResult authorize(String token, String pin) throws Exception {
        return mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("pin", pin)))).andReturn();
    }

    private MvcResult changePassword(String token, long userId, String oldPassword, String newPassword) throws Exception {
        return mockMvc.perform(post("/users/" + userId + "/change-password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("oldPassword", oldPassword, "newPassword", newPassword)))).andReturn();
    }

    private long createStaff(Map<String, Object> fields) throws Exception {
        var body = new HashMap<>(fields);
        body.put("name", "WS1 Monitor " + RANDOM.nextInt(1_000_000));
        var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn();
        assertThat(result.getResponse().getStatus()).as("create staff").isEqualTo(201);
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    /** A new admin with a random PIN no other admin uses; returns the PIN. */
    private String createAdminWithUniquePin() throws Exception {
        for (int tries = 0; tries < 5; tries++) {
            var pin = randomPin();
            var body = Map.<String, Object>of("name", "WS1 Monitor Admin", "role", "ADMIN",
                    "email", "ws1.admin." + RANDOM.nextInt(1_000_000_000) + "@example.test",
                    "password", randomPassword(), "pin", pin);
            var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn();
            if (result.getResponse().getStatus() == 201) {
                return pin;
            }
        }
        throw new IllegalStateException("Could not create an admin with a unique PIN");
    }

    private Session newCashierSession() throws Exception {
        var pin = randomPin();
        var id = createStaff(Map.of("role", "CASHIER", "pin", pin));
        return new Session(id, token(pinLogin(id, pin, "10.9.9.9")));
    }

    private static String token(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private static String json(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    private static String randomPin() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** A random 6-digit PIN; staff in this class never share it except by a one-in-a-million chance. */
    private static String wrongPin() {
        return randomPin();
    }

    /** 16 random letters and digits (staff passwords must be 6-25 characters). */
    private static String randomPassword() {
        var alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var builder = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return builder.toString();
    }
}
