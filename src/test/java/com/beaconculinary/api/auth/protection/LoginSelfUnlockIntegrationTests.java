package com.beaconculinary.api.auth.protection;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.TestStaffCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.beaconculinary.api.auth.protection.LoginProtectionErrorDto.ACCOUNT_LOCKED;
import static com.beaconculinary.api.auth.protection.LoginProtectionTestClient.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS1-1: an admin can't clear their own sign-in locks. Unlocking yourself is refused with 409
 * SELF_UNLOCK, and setting your own PIN or password saves it but keeps the lock. Another admin can
 * still unlock. Same settings as the other enforce tests, so the Spring context is shared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = {
        "security.login-protection.mode=enforce",
        "spring.datasource.hikari.connection-init-sql=SET LOCK_TIMEOUT 2000"
})
class LoginSelfUnlockIntegrationTests {
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
    private String seedAdminToken;
    private ListAppender<ILoggingEvent> alerts;
    private static boolean swept;

    /** A second admin, signed in with their own password. */
    private record Admin(long id, String email, String password, String token) {
    }

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        client = new LoginProtectionTestClient(mockMvc, jdbc, "WS1 Self");
        client.captureProtectionErrors();
        if (!swept) {
            TestStaffCleanup.deleteByNamePrefix(jdbc, "WS1 ");
            swept = true;
        }
        client.clearProtectionTables();
        seedAdminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        client.adminToken = seedAdminToken;
        alerts = new ListAppender<>();
        alerts.start();
        ((Logger) LoggerFactory.getLogger(LoginAttemptService.class)).addAppender(alerts);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(LoginAttemptService.class)).detachAppender(alerts);
        client.adminToken = seedAdminToken;
        client.cleanUp();
        client.clearProtectionTables();
        clock.reset();
        client.assertNoProtectionErrors();
    }

    private Admin secondAdmin() throws Exception {
        for (int tries = 0; tries < 5; tries++) {
            var email = "ws1.self." + RANDOM.nextInt(1_000_000_000) + "@example.test";
            var password = randomPassword();
            try {
                var id = client.createStaff(Map.of("role", "ADMIN", "email", email, "password", password, "pin", randomPin()));
                return new Admin(id, email, password, token(client.passwordLogin(email, password)));
            } catch (AssertionError pinInUse) {
                // Another admin already has that random PIN: try another.
            }
        }
        throw new IllegalStateException("Could not create a second admin");
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private void lock(long userId, LoginMethod scope) {
        assertThat(store.applyLock(userId, scope, now(), Duration.ofHours(24), LOCKS, "TEST")).isEqualTo(1);
    }

    private <T> T as(Admin admin, ThrowingSupplier<T> call) throws Exception {
        client.adminToken = admin.token();
        try {
            return call.get();
        } finally {
            client.adminToken = seedAdminToken;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private boolean alertLogged(String reason, long userId) {
        return alerts.list.stream().map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("LOGIN_PROTECTION_ALERT type=SELF_UNLOCK_REFUSED reason=" + reason + " user=" + userId));
    }

    @Test
    void unlockingYourself_isRefusedWith409_andAnotherAdminCanUnlock() throws Exception {
        var admin = secondAdmin();
        lock(admin.id(), LoginMethod.PASSWORD);

        MvcResult self = as(admin, () -> client.unlock(admin.id(), null));
        assertThat(self.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(self)).isEqualTo(MAPPER.valueToTree(Map.of(
                "error", "You can't unlock your own sign-in. Ask another admin to do it.",
                "code", "SELF_UNLOCK")));
        assertThat(client.staff(admin.id()).get("locks").get("PASSWORD").get("locked").asBoolean()).isTrue();
        assertThat(alertLogged("ADMIN_UNLOCK", admin.id())).isTrue();

        var byOther = client.unlock(admin.id(), null);
        assertThat(byOther.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(byOther).get("locks").get("PASSWORD").get("locked").asBoolean()).isFalse();
    }

    @Test
    void settingYourOwnPin_savesIt_butKeepsYourPinLock() throws Exception {
        var admin = secondAdmin();
        lock(admin.id(), LoginMethod.PIN);

        MvcResult result = null;
        String newPin = null;
        for (int tries = 0; tries < 5; tries++) {
            newPin = randomPin();
            final var candidate = newPin;
            result = as(admin, () -> client.setPin(admin.id(), candidate));
            if (result.getResponse().getStatus() != 409) {
                break;
            }
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("locks").get("PIN").get("locked").asBoolean()).isTrue();
        assertThat(alertLogged("PIN_RESET", admin.id())).isTrue();

        clock.setInstant(clock.instant().plusSeconds(1));
        assertRefused(client.pinLogin(admin.id(), newPin, "10.20.0.1"), ACCOUNT_LOCKED, 899L);
    }

    @Test
    void settingYourOwnPassword_savesIt_butKeepsYourPasswordLock() throws Exception {
        var admin = secondAdmin();
        lock(admin.id(), LoginMethod.PASSWORD);
        var newPassword = randomPassword();

        MvcResult result = as(admin, () -> client.setPassword(admin.id(), newPassword));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("locks").get("PASSWORD").get("locked").asBoolean()).isTrue();
        assertThat(alertLogged("PASSWORD_RESET", admin.id())).isTrue();

        clock.setInstant(clock.instant().plusSeconds(1));
        assertRefused(client.passwordLogin(admin.email(), newPassword), ACCOUNT_LOCKED, 899L);
    }

    @Test
    void anotherAdminSettingYourPin_stillClearsTheLock() throws Exception {
        var admin = secondAdmin();
        lock(admin.id(), LoginMethod.PIN);

        MvcResult result = null;
        for (int tries = 0; tries < 5; tries++) {
            result = client.setPin(admin.id(), randomPin());
            if (result.getResponse().getStatus() != 409) {
                break;
            }
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("locks").get("PIN").get("locked").asBoolean()).isFalse();
    }
}
