package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.common.UtcTimestamps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SQL behind login protection: the conditional lock statement used by enforce mode (Phase 2),
 * the success marker, and the retention job. Uses the test database; cleans both tables first.
 */
@SpringBootTest
class LoginProtectionStoreIntegrationTests {
    private static final List<Duration> LOCKS = List.of(Duration.ofMinutes(15), Duration.ofMinutes(60));
    private static final Duration RESET = Duration.ofHours(24);

    @Autowired
    private LoginProtectionStore store;
    @Autowired
    private LoginAttemptRetentionJob retentionJob;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Clock clock;

    private long userId;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM [login_attempts]");
        jdbc.update("DELETE FROM [user_auth_state]");
        userId = jdbc.queryForObject("SELECT TOP 1 [id] FROM [users] ORDER BY [id]", Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM [login_attempts]");
        jdbc.update("DELETE FROM [user_auth_state]");
    }

    @Test
    void applyLock_escalatesFifteenMinutes_thenOneHour_thenAdminUnlock() {
        var t0 = LocalDateTime.of(2026, 1, 1, 8, 0);
        assertThat(store.applyLock(userId, LoginMethod.PASSWORD, t0, RESET, LOCKS, "PASSWORD_ACCOUNT")).isEqualTo(1);
        assertThat(lockedUntil()).isEqualTo(t0.plusMinutes(15));

        // Still locked: nothing changes.
        assertThat(store.applyLock(userId, LoginMethod.PASSWORD, t0.plusMinutes(5), RESET, LOCKS, "PASSWORD_ACCOUNT")).isZero();

        var t1 = t0.plusMinutes(20);
        assertThat(store.applyLock(userId, LoginMethod.PASSWORD, t1, RESET, LOCKS, "PASSWORD_ACCOUNT")).isEqualTo(2);
        assertThat(lockedUntil()).isEqualTo(t1.plusMinutes(60));

        var t2 = t1.plusMinutes(61);
        assertThat(store.applyLock(userId, LoginMethod.PASSWORD, t2, RESET, LOCKS, "PASSWORD_ACCOUNT")).isEqualTo(3);
        assertThat(lockedUntil().getYear()).isEqualTo(9999);
    }

    @Test
    void applyLock_startsAgainAtLevelOne_afterASuccess_orAfterTheResetPeriod() {
        var t0 = LocalDateTime.of(2026, 1, 1, 8, 0);
        store.applyLock(userId, LoginMethod.PIN, t0, RESET, LOCKS, "PIN_ACCOUNT_CEILING");
        store.markSuccess(userId, LoginMethod.PIN, t0.plusMinutes(30));
        assertThat(store.applyLock(userId, LoginMethod.PIN, t0.plusMinutes(40), RESET, LOCKS, "PIN_ACCOUNT_CEILING")).isEqualTo(1);

        assertThat(store.applyLock(userId, LoginMethod.PIN, t0.plusHours(30), RESET, LOCKS, "PIN_ACCOUNT_CEILING")).isEqualTo(1);
    }

    @Test
    void markSuccess_insertsThenUpdates_andScopesAreSeparate() {
        var t0 = LocalDateTime.of(2026, 1, 1, 8, 0);
        store.markSuccess(userId, LoginMethod.PIN, t0);
        store.markSuccess(userId, LoginMethod.PIN, t0.plusMinutes(1));
        assertThat(store.lastSuccess(userId, LoginMethod.PIN)).isEqualTo(t0.plusMinutes(1));
        assertThat(store.lastSuccess(userId, LoginMethod.PASSWORD)).isNull();
    }

    @Test
    void retention_deletesOnlyRowsOlderThanNinetyDays() {
        var now = UtcTimestamps.nowUtc(clock);
        insertAttempt(now.minusDays(91));
        insertAttempt(now.minusDays(90).minusMinutes(1));
        insertAttempt(now.minusDays(89));
        insertAttempt(now);

        assertThat(retentionJob.purge()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [login_attempts]", Integer.class)).isEqualTo(2);
    }

    private LocalDateTime lockedUntil() {
        return jdbc.queryForObject("SELECT [locked_until] FROM [user_auth_state] WHERE [user_id] = ? AND [scope] = 'PASSWORD'",
                LocalDateTime.class, userId);
    }

    private void insertAttempt(LocalDateTime at) {
        store.saveAttempt(new LoginProtectionStore.AttemptRow(at, LoginMethod.PASSWORD, "someone@example.test", null,
                "203.0.113.7", "203.0.113.7", null, null, LoginOutcome.BAD_CREDENTIAL, false, null, null), null);
    }
}
