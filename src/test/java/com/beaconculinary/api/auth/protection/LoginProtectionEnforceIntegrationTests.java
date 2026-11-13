package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.TestStaffCleanup;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.beaconculinary.api.auth.protection.LoginProtectionErrorDto.ACCOUNT_LOCKED;
import static com.beaconculinary.api.auth.protection.LoginProtectionErrorDto.RATE_LIMITED;
import static com.beaconculinary.api.auth.protection.LoginProtectionTestClient.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WS1-1 Phase 2, enforce mode: every rule refuses at the right attempt with the agreed 429 contract,
 * before any credential check, and locks escalate 15 min, 60 min, then admin unlock.
 *
 * <p>Time is driven by the test clock (one second per attempt), so Retry-After values are exact.
 * Every pooled connection has SET LOCK_TIMEOUT 2000: a self-blocking lock write would fail the
 * test instead of hanging. Staff are created with random credentials and deleted afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = {
        "security.login-protection.mode=enforce",
        "spring.datasource.hikari.connection-init-sql=SET LOCK_TIMEOUT 2000"
})
class LoginProtectionEnforceIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private LoginProtectionTestClient client;
    private static boolean swept;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        client = new LoginProtectionTestClient(mockMvc, jdbc, "WS1 Enf");
        client.captureProtectionErrors();
        if (!swept) {
            TestStaffCleanup.deleteByNamePrefix(jdbc, "WS1 ");
            swept = true;
        }
        client.clearProtectionTables();
        client.adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        client.cleanUp();
        client.clearProtectionTables();
        clock.reset();
        client.assertNoProtectionErrors();
    }

    private void tick() {
        advance(Duration.ofSeconds(1));
    }

    private void advance(Duration duration) {
        clock.setInstant(clock.instant().plus(duration));
    }

    // ---- PASSWORD_ACCOUNT ----

    @Test
    void password_fiveFailuresLock_theSixthIsRefusedEvenWithTheRightPassword_andTheLockEndsByItself() throws Exception {
        var user = client.passwordUser();
        for (int i = 0; i < 5; i++) {
            tick();
            assertUnauthorizedEmpty(client.passwordLogin(user.email(), randomPassword()));
        }
        tick();
        assertRefused(client.passwordLogin(user.email(), user.password()), ACCOUNT_LOCKED, 899L);

        var rows = attempts("PASSWORD", user.email());
        assertThat(rows.get(4).get("lock_level")).isEqualTo(1);
        assertThat(rows.get(4).get("rule_triggered")).isEqualTo("PASSWORD_ACCOUNT");
        assertThat(rows.get(5).get("outcome")).isEqualTo("LOCKED");
        assertThat(rows.get(5).get("would_block")).isEqualTo(true);

        advance(Duration.ofSeconds(900));
        assertThat(client.passwordLogin(user.email(), user.password()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void password_escalatesFifteenMinutes_thenAnHour_thenAdminUnlock_identicallyForAnUnknownEmail() throws Exception {
        var user = client.passwordUser();
        var unknown = "nobody." + RANDOM.nextInt(1_000_000_000) + "@example.test";
        long[] expected = {899, 3599};

        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 5; i++) {
                tick();
                assertUnauthorizedEmpty(client.passwordLogin(user.email(), randomPassword()));
                assertUnauthorizedEmpty(client.passwordLogin(unknown, randomPassword()));
            }
            tick();
            var real = client.passwordLogin(user.email(), user.password());
            var ghost = client.passwordLogin(unknown, randomPassword());
            var retry = round < 2 ? expected[round] : null;
            assertRefused(real, ACCOUNT_LOCKED, retry);
            assertRefused(ghost, ACCOUNT_LOCKED, retry);
            assertThat(real.getResponse().getContentAsString()).isEqualTo(ghost.getResponse().getContentAsString());
            assertThat(real.getResponse().getHeader("Retry-After")).isEqualTo(ghost.getResponse().getHeader("Retry-After"));
            if (round == 0) {
                advance(Duration.ofSeconds(900));
            } else if (round == 1) {
                advance(Duration.ofSeconds(3600));
            }
        }

        // Level 3 does not end by itself.
        advance(Duration.ofDays(2));
        assertRefused(client.passwordLogin(user.email(), user.password()), ACCOUNT_LOCKED, null);
        assertRefused(client.passwordLogin(unknown, randomPassword()), ACCOUNT_LOCKED, null);
    }

    @Test
    void refresh_isRefusedWhileThePasswordSignInIsLocked() throws Exception {
        var user = client.passwordUser();
        tick();
        var login = client.passwordLogin(user.email(), user.password());
        var cookie = login.getResponse().getCookie("refreshToken");
        assertThat(cookie).isNotNull();
        assertThat(client.refresh(cookie).getResponse().getStatus()).isEqualTo(200);

        for (int i = 0; i < 5; i++) {
            tick();
            client.passwordLogin(user.email(), randomPassword());
        }
        assertUnauthorizedEmpty(client.refresh(cookie));

        advance(Duration.ofSeconds(901));
        assertThat(client.refresh(cookie).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void corsExposesRetryAfter_onA429() throws Exception {
        var user = client.passwordUser();
        for (int i = 0; i < 5; i++) {
            tick();
            client.passwordLogin(user.email(), randomPassword());
        }
        tick();
        var refused = client.passwordLogin(user.email(), user.password());
        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getHeader("Access-Control-Expose-Headers")).contains("Retry-After");
        assertThat(refused.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
    }

    // ---- PIN_PAIR and PIN_ACCOUNT_CEILING ----

    @Test
    void pinPair_blocksTheSixthFromThatAddress_otherAddressesStillSignIn_andTheBlockEndsWithTheWindow() throws Exception {
        var cashier = client.cashier();
        for (int i = 0; i < 5; i++) {
            tick();
            assertUnauthorizedEmpty(client.pinLogin(cashier.id(), wrongPin(cashier.pin()), "10.1.0.1"));
        }
        tick();
        // The oldest of the five failures leaves the 15-minute window 895 seconds from now.
        assertRefused(client.pinLogin(cashier.id(), cashier.pin(), "10.1.0.1"), RATE_LIMITED, 895L);
        assertThat(attempts("PIN", String.valueOf(cashier.id())).get(5).get("outcome")).isEqualTo("RATE_LIMITED");

        advance(Duration.ofSeconds(895));
        assertThat(client.pinLogin(cashier.id(), cashier.pin(), "10.1.0.1").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void pinPair_doesNotBlockTheSameCashierElsewhere() throws Exception {
        var cashier = client.cashier();
        for (int i = 0; i < 5; i++) {
            tick();
            client.pinLogin(cashier.id(), wrongPin(cashier.pin()), "10.2.0.1");
        }
        tick();
        assertThat(client.pinLogin(cashier.id(), wrongPin(cashier.pin()), "10.2.0.1").getResponse().getStatus()).isEqualTo(429);
        assertThat(client.pinLogin(cashier.id(), cashier.pin(), "10.2.0.2").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void pinCeiling_twentyFailuresFromAnywhere_lockTheAccount_identicallyForAnUnknownCashier() throws Exception {
        var cashier = client.cashier();
        var unknownId = 900_000_000L + RANDOM.nextInt(1_000_000);
        for (var ip : List.of("10.3.0.1", "10.3.0.2", "10.3.0.3", "10.3.0.4")) {
            for (int i = 0; i < 5; i++) {
                tick();
                assertUnauthorizedEmpty(client.pinLogin(cashier.id(), wrongPin(cashier.pin()), ip));
                assertUnauthorizedEmpty(client.pinLogin(unknownId, randomPin(), ip));
            }
        }
        tick();
        var real = client.pinLogin(cashier.id(), cashier.pin(), "10.3.0.5");
        var ghost = client.pinLogin(unknownId, randomPin(), "10.3.0.5");
        assertRefused(real, ACCOUNT_LOCKED, 899L);
        assertRefused(ghost, ACCOUNT_LOCKED, 899L);
        assertThat(real.getResponse().getContentAsString()).isEqualTo(ghost.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT [lock_level] FROM [user_auth_state] WHERE [user_id] = ? AND [scope] = 'PIN'",
                Integer.class, cashier.id())).isEqualTo(1);
    }

    // ---- MANAGER_PIN_CALLER and CHANGE_PASSWORD_USER ----

    @Test
    void managerPin_fiveWrong_blockTheCaller_evenWithTheRightPin_wrongPinsKeepTheirBody() throws Exception {
        var adminPin = client.adminPin();
        var caller = client.cashier();
        var callerToken = token(client.pinLogin(caller.id(), caller.pin(), "10.4.0.1"));
        for (int i = 0; i < 5; i++) {
            tick();
            var wrong = client.authorize(callerToken, wrongPin(adminPin));
            assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
            assertThat(body(wrong).get("error").asText()).isEqualTo("Invalid PIN.");
        }
        tick();
        assertRefused(client.authorize(callerToken, adminPin), RATE_LIMITED, 895L);
    }

    @Test
    void changePassword_fiveWrong_blockChangePassword_butNotSignIn() throws Exception {
        var user = client.passwordUser();
        tick();
        var token = token(client.passwordLogin(user.email(), user.password()));
        for (int i = 0; i < 5; i++) {
            tick();
            assertUnauthorizedEmpty(client.changePassword(token, user.id(), randomPassword(), randomPassword()));
        }
        tick();
        assertRefused(client.changePassword(token, user.id(), user.password(), randomPassword()), RATE_LIMITED, 895L);
        tick();
        assertThat(client.passwordLogin(user.email(), user.password()).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- 6-digit PINs ----

    @Test
    void pins_mustBeExactlySixDigits() throws Exception {
        var cashier = client.cashier();
        for (var pin : List.of("12345", "1234567", "12a456")) {
            var result = client.pinLogin(cashier.id(), pin, "10.5.0.1");
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(result)).isEqualTo(MAPPER.valueToTree(Map.of("pin", "PIN must be 6 digits")));
        }
        var token = token(client.pinLogin(cashier.id(), cashier.pin(), "10.5.0.1"));
        var authorize = mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}")).andReturn();
        assertThat(authorize.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(authorize).get("pin").asText()).isEqualTo("PIN must be 6 digits");
    }

    private List<Map<String, Object>> attempts(String method, String identifier) {
        return jdbc.queryForList("SELECT * FROM [login_attempts] WHERE [method] = ? AND [identifier] = ? ORDER BY [id]",
                method, identifier);
    }
}
