package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.TestStaffCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.beaconculinary.api.auth.protection.LoginProtectionErrorDto.ACCOUNT_LOCKED;
import static com.beaconculinary.api.auth.protection.LoginProtectionErrorDto.RATE_LIMITED;
import static com.beaconculinary.api.auth.protection.LoginProtectionTestClient.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS1-1 Phase 2: admin unlock, clearing locks with a new PIN or password, and the lock state on
 * the staff page. Enforce mode, test clock, and SET LOCK_TIMEOUT 2000 on every connection: unlock
 * writes run inside the staff-management transaction that has already written the users row, so a
 * separate transaction would wait on it and fail here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = {
        "security.login-protection.mode=enforce",
        "spring.datasource.hikari.connection-init-sql=SET LOCK_TIMEOUT 2000"
})
class LoginUnlockIntegrationTests {
    private static final List<Duration> LOCKS = List.of(Duration.ofMinutes(15), Duration.ofMinutes(60));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private LoginProtectionStore store;

    private LoginProtectionTestClient client;
    private long adminId;
    private static boolean swept;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        client = new LoginProtectionTestClient(mockMvc, jdbc, "WS1 Unl");
        client.captureProtectionErrors();
        if (!swept) {
            TestStaffCleanup.deleteByNamePrefix(jdbc, "WS1 ");
            swept = true;
        }
        client.clearProtectionTables();
        client.adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        adminId = AuthTestHelper.adminId(mockMvc);
    }

    @AfterEach
    void tearDown() {
        client.cleanUp();
        client.clearProtectionTables();
        clock.reset();
        client.assertNoProtectionErrors();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private void tick() {
        clock.setInstant(clock.instant().plusSeconds(1));
    }

    private void lockPasswordBySignIn(LoginProtectionTestClient.Account user) throws Exception {
        for (int i = 0; i < 5; i++) {
            tick();
            client.passwordLogin(user.email(), randomPassword());
        }
        tick();
        assertThat(client.passwordLogin(user.email(), user.password()).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void staffPage_showsLocks_andUnlockOneScope_thenAll() throws Exception {
        var chef = client.chefWithPin();
        lockPasswordBySignIn(chef);
        assertThat(store.applyLock(chef.id(), LoginMethod.PIN, now(), Duration.ofHours(24), LOCKS, "PIN_ACCOUNT_CEILING")).isEqualTo(1);

        var locks = client.staff(chef.id()).get("locks");
        assertThat(locks.get("PASSWORD").get("locked").asBoolean()).isTrue();
        assertThat(locks.get("PASSWORD").get("lockedUntil").asText()).endsWith("Z");
        assertThat(locks.get("PASSWORD").get("requiresAdminUnlock").asBoolean()).isFalse();
        assertThat(locks.get("PIN").get("locked").asBoolean()).isTrue();

        var onePin = client.unlock(chef.id(), "PIN");
        assertThat(onePin.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(onePin).get("locks").get("PIN").get("locked").asBoolean()).isFalse();
        assertThat(body(onePin).get("locks").get("PASSWORD").get("locked").asBoolean()).isTrue();
        tick();
        assertThat(client.pinLogin(chef.id(), chef.pin(), "10.6.0.1").getResponse().getStatus()).isEqualTo(200);
        tick();
        assertRefused(client.passwordLogin(chef.email(), chef.password()), ACCOUNT_LOCKED, 897L);

        var all = client.unlock(chef.id(), null);
        assertThat(all.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(all).get("locks").get("PASSWORD").get("locked").asBoolean()).isFalse();
        tick();
        assertThat(client.passwordLogin(chef.email(), chef.password()).getResponse().getStatus()).isEqualTo(200);

        var state = jdbc.queryForMap("SELECT [lock_level], [locked_until], [unlocked_by] FROM [user_auth_state] WHERE [user_id] = ? AND [scope] = 'PASSWORD'", chef.id());
        assertThat(state.get("lock_level")).isEqualTo(0);
        assertThat(state.get("locked_until")).isNull();
        assertThat(((Number) state.get("unlocked_by")).longValue()).isEqualTo(adminId);
    }

    @Test
    void levelThree_needsAnAdmin_andTheStaffPageSaysSo() throws Exception {
        var cashier = client.cashier();
        var t = now();
        store.applyLock(cashier.id(), LoginMethod.PIN, t.minusHours(3), Duration.ofHours(24), LOCKS, "PIN_ACCOUNT_CEILING");
        store.applyLock(cashier.id(), LoginMethod.PIN, t.minusHours(2), Duration.ofHours(24), LOCKS, "PIN_ACCOUNT_CEILING");
        assertThat(store.applyLock(cashier.id(), LoginMethod.PIN, t.minusMinutes(1), Duration.ofHours(24), LOCKS, "PIN_ACCOUNT_CEILING")).isEqualTo(3);

        var pin = client.staff(cashier.id()).get("locks").get("PIN");
        assertThat(pin.get("locked").asBoolean()).isTrue();
        assertThat(pin.get("lockedUntil").isNull()).isTrue();
        assertThat(pin.get("requiresAdminUnlock").asBoolean()).isTrue();
        tick();
        assertRefused(client.pinLogin(cashier.id(), cashier.pin(), "10.7.0.1"), ACCOUNT_LOCKED, null);

        assertThat(client.unlock(cashier.id(), null).getResponse().getStatus()).isEqualTo(200);
        tick();
        assertThat(client.pinLogin(cashier.id(), cashier.pin(), "10.7.0.1").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unlock_alsoEndsWindowBlocks() throws Exception {
        var user = client.passwordUser();
        tick();
        var token = token(client.passwordLogin(user.email(), user.password()));
        for (int i = 0; i < 5; i++) {
            tick();
            client.changePassword(token, user.id(), randomPassword(), randomPassword());
        }
        tick();
        assertRefused(client.changePassword(token, user.id(), user.password(), randomPassword()), RATE_LIMITED, 895L);

        assertThat(client.unlock(user.id(), "CHANGE_PASSWORD").getResponse().getStatus()).isEqualTo(200);
        tick();
        assertThat(client.changePassword(token, user.id(), user.password(), randomPassword()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void newPin_clearsThePinLock_inTheStaffTransaction() throws Exception {
        var cashier = client.cashier();
        store.applyLock(cashier.id(), LoginMethod.PIN, now(), Duration.ofHours(24), LOCKS, "PIN_ACCOUNT_CEILING");
        tick();
        assertThat(client.pinLogin(cashier.id(), cashier.pin(), "10.8.0.1").getResponse().getStatus()).isEqualTo(429);

        var newPin = wrongPin(cashier.pin());
        var result = client.setPin(cashier.id(), newPin);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("locks").get("PIN").get("locked").asBoolean()).isFalse();
        tick();
        assertThat(client.pinLogin(cashier.id(), newPin, "10.8.0.1").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void newPin_forAUserWithNoStateRow_writesItInTheSameTransaction() throws Exception {
        var cashier = client.cashier();
        assertThat(client.setPin(cashier.id(), wrongPin(cashier.pin())).getResponse().getStatus()).isEqualTo(200);
        var unlockedAt = jdbc.queryForList("SELECT [unlocked_at] FROM [user_auth_state] WHERE [user_id] = ? AND [scope] = 'PIN'",
                LocalDateTime.class, cashier.id());
        assertThat(unlockedAt).singleElement().isNotNull();
    }

    @Test
    void newPassword_clearsThePasswordLock() throws Exception {
        var user = client.passwordUser();
        lockPasswordBySignIn(user);
        var newPassword = randomPassword();
        var result = client.setPassword(user.id(), newPassword);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("locks").get("PASSWORD").get("locked").asBoolean()).isFalse();
        tick();
        assertThat(client.passwordLogin(user.email(), newPassword).getResponse().getStatus()).isEqualTo(200);
    }

    /** Regression: an unlocked user who has never signed in has unlocked_at but no last_success_at. */
    @Test
    void afterAnUnlock_failuresAreStillCountedAndRefused() throws Exception {
        var cashier = client.cashier();
        assertThat(client.unlock(cashier.id(), null).getResponse().getStatus()).isEqualTo(200);
        for (int i = 0; i < 5; i++) {
            tick();
            assertUnauthorizedEmpty(client.pinLogin(cashier.id(), wrongPin(cashier.pin()), "10.10.0.1"));
        }
        tick();
        assertRefused(client.pinLogin(cashier.id(), cashier.pin(), "10.10.0.1"), RATE_LIMITED, 895L);
    }

    @Test
    void unlock_rejectsAnUnknownScope_andAnUnknownUser() throws Exception {
        var cashier = client.cashier();
        var invalid = client.unlock(cashier.id(), "EVERYTHING");
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(invalid).get("scope").asText()).isEqualTo("Scope must be one of PASSWORD, PIN, MANAGER_PIN, CHANGE_PASSWORD.");
        assertThat(client.unlock(987_654_321L, null).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void staffList_showsLockState() throws Exception {
        var cashier = client.cashier();
        store.applyLock(cashier.id(), LoginMethod.PIN, now(), Duration.ofHours(24), LOCKS, "PIN_ACCOUNT_CEILING");
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/staff")
                .header("Authorization", "Bearer " + client.adminToken)).andReturn();
        var mine = MAPPER.readTree(result.getResponse().getContentAsString()).findParents("id").stream()
                .filter(node -> node.get("id").asLong() == cashier.id()).findFirst().orElseThrow();
        assertThat(mine.get("locks").get("PIN").get("locked").asBoolean()).isTrue();
        assertThat(mine.get("locks").get("PASSWORD").get("locked").asBoolean()).isFalse();
    }
}
