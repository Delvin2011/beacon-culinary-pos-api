package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.TestStaffCleanup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Transaction safety of the REQUIRES_NEW attempt writes (LoginProtectionStore.saveAttempt, which
 * also runs markSuccess).
 *
 * <p>The danger: if a request's own transaction wrote a users row and then the REQUIRES_NEW
 * transaction inserted that user's first user_auth_state row, the foreign-key check would wait on
 * the outer transaction, which is waiting for it on the same thread. SQL Server sees two sessions
 * and cannot detect it, so the request would hang.
 *
 * <p>Every connection here runs with SET LOCK_TIMEOUT 2000, so such a wait fails after 2 seconds
 * instead of hanging. Recording errors are logged and swallowed, so a block shows up as a missing
 * attempt row and a missing last_success_at. Each path below uses a fresh user with no state row,
 * which is the case that runs the foreign-key check. The negative control proves the detection works.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.login-protection.mode=monitor",
        "spring.datasource.hikari.connection-init-sql=SET LOCK_TIMEOUT 2000"
})
class LoginProtectionTransactionIntegrationTests {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LoginAttemptService loginAttempts;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private String adminToken;
    private final List<Long> createdUserIds = new ArrayList<>();
    private static boolean swept;

    @BeforeEach
    void setUp() throws Exception {
        if (!swept) {
            TestStaffCleanup.deleteByNamePrefix(jdbc, "WS1 ");
            swept = true;
        }
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void deleteCreatedStaff() {
        TestStaffCleanup.delete(jdbc, createdUserIds);
        createdUserIds.clear();
    }

    @Test
    void lockTimeoutIsActiveOnPooledConnections() {
        assertThat(jdbc.queryForObject("SELECT @@LOCK_TIMEOUT", Integer.class)).isEqualTo(2000);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void passwordLogin_recordsWithoutWaitingOnTheCaller() throws Exception {
        var email = "ws1.txn." + RANDOM.nextInt(1_000_000_000) + "@example.test";
        var password = randomPassword();
        var userId = createStaff(Map.of("role", "STOCK_CLERK", "email", email, "password", password));

        assertThat(passwordLogin(email, password).getResponse().getStatus()).isEqualTo(200);

        assertRecordedSuccess("PASSWORD", userId);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void pinLogin_recordsWithoutWaitingOnTheCaller() throws Exception {
        var pin = randomPin();
        var cashierId = createStaff(Map.of("role", "CASHIER", "pin", pin));

        assertThat(pinLogin(cashierId, pin).getResponse().getStatus()).isEqualTo(200);

        assertRecordedSuccess("PIN", cashierId);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void changePassword_recordsBeforeTheUsersRowIsWritten() throws Exception {
        var email = "ws1.txn." + RANDOM.nextInt(1_000_000_000) + "@example.test";
        var password = randomPassword();
        var newPassword = randomPassword();
        var userId = createStaff(Map.of("role", "STOCK_CLERK", "email", email, "password", password));
        var token = token(passwordLogin(email, password));

        var result = mockMvc.perform(post("/users/" + userId + "/change-password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("oldPassword", password, "newPassword", newPassword)))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        assertRecordedSuccess("CHANGE_PASSWORD", userId);
        // The users row was written after the attempt was recorded: the new password works.
        assertThat(passwordLogin(email, newPassword).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void authorize_recordsInsideTheOuterTransaction() throws Exception {
        var adminPin = createAdminWithUniquePin();
        var callerPin = randomPin();
        var callerId = createStaff(Map.of("role", "CASHIER", "pin", callerPin));
        var callerToken = token(pinLogin(callerId, callerPin));

        var result = authorize("/admin/authorize", callerToken, adminPin);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"authorizationToken\"");

        assertRecordedSuccess("MANAGER_PIN", callerId);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void authorizeSession_recordsInsideTheOuterTransaction() throws Exception {
        var adminPin = createAdminWithUniquePin();
        var callerPin = randomPin();
        var callerId = createStaff(Map.of("role", "CASHIER", "pin", callerPin));
        var callerToken = token(pinLogin(callerId, callerPin));

        var result = authorize("/admin/authorize-session", callerToken, adminPin);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"sessionToken\"");

        assertRecordedSuccess("MANAGER_PIN", callerId);
    }

    /**
     * The broken ordering on purpose: the outer transaction writes the users row, then a success is
     * recorded for that user with no state row yet. The REQUIRES_NEW foreign-key check must wait,
     * give up after the 2-second lock timeout, and leave nothing behind, rather than hang.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void negativeControl_outerWriteToUsersRow_isDetected() throws Exception {
        var userId = createStaff(Map.of("role", "CASHIER", "pin", randomPin()));
        var identifier = "negative-control-" + userId;
        var started = System.nanoTime();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE [users] SET [name] = ? WHERE [id] = ?", "WS1 Txn Locked", userId);
            var attempt = loginAttempts.begin(LoginMethod.PIN, identifier, userId);
            loginAttempts.finish(attempt, LoginOutcome.SUCCESS, userId, false);
            status.setRollbackOnly();
        });

        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMs).as("waited for the lock timeout").isGreaterThanOrEqualTo(1900);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [user_auth_state] WHERE [user_id] = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [login_attempts] WHERE [identifier] = ?", Integer.class, identifier)).isZero();
    }

    // ---- helpers ----

    private void assertRecordedSuccess(String method, long userId) {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM [login_attempts] WHERE [method] = ? AND [user_id] = ? AND [outcome] = 'SUCCESS'",
                Integer.class, method, userId)).as("%s success recorded", method).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM [user_auth_state] WHERE [user_id] = ? AND [scope] = ? AND [last_success_at] IS NOT NULL",
                Integer.class, userId, method)).as("%s last_success_at written", method).isEqualTo(1);
    }

    private MvcResult passwordLogin(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password)))).andReturn();
    }

    private MvcResult pinLogin(long cashierId, String pin) throws Exception {
        return mockMvc.perform(post("/auth/pin-login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("cashierId", cashierId, "pin", pin)))).andReturn();
    }

    private MvcResult authorize(String path, String token, String pin) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("pin", pin)))).andReturn();
    }

    private long createStaff(Map<String, Object> fields) throws Exception {
        var body = new HashMap<>(fields);
        body.put("name", "WS1 Txn " + RANDOM.nextInt(1_000_000));
        var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn();
        assertThat(result.getResponse().getStatus()).as("create staff").isEqualTo(201);
        var id = MAPPER.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        createdUserIds.add(id);
        return id;
    }

    private String createAdminWithUniquePin() throws Exception {
        for (int tries = 0; tries < 5; tries++) {
            var pin = randomPin();
            var body = Map.<String, Object>of("name", "WS1 Txn Admin", "role", "ADMIN",
                    "email", "ws1.txn.admin." + RANDOM.nextInt(1_000_000_000) + "@example.test",
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

    private static String randomPassword() {
        var alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var builder = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return builder.toString();
    }
}
