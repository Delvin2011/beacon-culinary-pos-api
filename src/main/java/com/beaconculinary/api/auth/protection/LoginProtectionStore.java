package com.beaconculinary.api.auth.protection;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SQL for login_attempts and user_auth_state (V103). All times are UTC LocalDateTime values.
 * Failures are the outcomes BAD_CREDENTIAL, UNKNOWN_USER and DISABLED.
 */
@Repository
public class LoginProtectionStore {
    static final String ADMIN_UNLOCK_UNTIL = "9999-12-31T23:59:59.9999999";
    private static final String FAILURE = "[outcome] IN ('BAD_CREDENTIAL', 'UNKNOWN_USER', 'DISABLED')";

    private final JdbcTemplate jdbc;

    public LoginProtectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One login_attempts row. Strings must already fit their columns. */
    public record AttemptRow(LocalDateTime attemptedAt, LoginMethod method, String identifier, Long userId,
                             String clientIp, String remoteAddr, String forwardedFor, String userAgent,
                             LoginOutcome outcome, boolean wouldBlock, String ruleTriggered, Boolean pinNonStandard) {
    }

    public long countFailuresByIdentifier(LoginMethod method, String identifier, LocalDateTime after) {
        if (identifier == null) {
            return 0;
        }
        return count("SELECT COUNT(*) FROM [login_attempts] WHERE [method] = ? AND [identifier] = ? AND [attempted_at] > ? AND " + FAILURE,
                method.name(), identifier, after);
    }

    public long countFailuresByIdentifierAndIp(LoginMethod method, String identifier, String clientIp, LocalDateTime after) {
        if (identifier == null) {
            return 0;
        }
        if (clientIp == null) {
            return count("SELECT COUNT(*) FROM [login_attempts] WHERE [method] = ? AND [identifier] = ? AND [client_ip] IS NULL AND [attempted_at] > ? AND " + FAILURE,
                    method.name(), identifier, after);
        }
        return count("SELECT COUNT(*) FROM [login_attempts] WHERE [method] = ? AND [identifier] = ? AND [client_ip] = ? AND [attempted_at] > ? AND " + FAILURE,
                method.name(), identifier, clientIp, after);
    }

    public long countFailuresByUser(LoginMethod method, Long userId, LocalDateTime after) {
        if (userId == null) {
            return 0;
        }
        return count("SELECT COUNT(*) FROM [login_attempts] WHERE [method] = ? AND [user_id] = ? AND [attempted_at] > ? AND " + FAILURE,
                method.name(), userId, after);
    }

    public long countFailures(LoginMethod method, LocalDateTime after) {
        return count("SELECT COUNT(*) FROM [login_attempts] WHERE [method] = ? AND [attempted_at] > ? AND " + FAILURE,
                method.name(), after);
    }

    /**
     * How many would-be locks for this rule started after {@code after}, for monitor mode, where no
     * lock is ever written. A lock "starts" at an attempt flagged with the rule whose previous
     * attempt for the same key was not.
     */
    public long countLockStarts(LoginMethod method, String identifier, LoginRule rule, LocalDateTime after) {
        if (identifier == null) {
            return 0;
        }
        return count("""
                SELECT COUNT(*) FROM (
                    SELECT [rule_triggered], [would_block],
                           LAG([rule_triggered]) OVER (ORDER BY [attempted_at], [id]) AS [prev_rule]
                    FROM [login_attempts]
                    WHERE [method] = ? AND [identifier] = ? AND [attempted_at] > ?
                ) x
                WHERE x.[would_block] = 1 AND x.[rule_triggered] = ? AND (x.[prev_rule] IS NULL OR x.[prev_rule] <> ?)
                """, method.name(), identifier, after, rule.name(), rule.name());
    }

    /** The rule_triggered of the latest attempt for this key after {@code after}; empty when none or not flagged. */
    public java.util.Optional<String> latestRule(LoginMethod method, String identifier, LocalDateTime after) {
        if (identifier == null) {
            return java.util.Optional.empty();
        }
        List<String> rows = jdbc.queryForList("""
                SELECT TOP 1 CASE WHEN [would_block] = 1 THEN [rule_triggered] END
                FROM [login_attempts]
                WHERE [method] = ? AND [identifier] = ? AND [attempted_at] > ?
                ORDER BY [attempted_at] DESC, [id] DESC
                """, String.class, method.name(), identifier, after);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.ofNullable(rows.get(0));
    }

    public LocalDateTime lastSuccess(Long userId, LoginMethod scope) {
        if (userId == null) {
            return null;
        }
        List<LocalDateTime> rows = jdbc.queryForList(
                "SELECT [last_success_at] FROM [user_auth_state] WHERE [user_id] = ? AND [scope] = ?",
                LocalDateTime.class, userId, scope.name());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Writes the attempt and, for a success by a known user, its last_success_at, in a transaction
     * of its own: a failure is kept even when the caller's transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAttempt(AttemptRow row, Long successUserId) {
        jdbc.update("""
                INSERT INTO [login_attempts] ([attempted_at], [method], [identifier], [user_id], [client_ip], [remote_addr],
                    [forwarded_for], [user_agent], [device_id], [outcome], [would_block], [rule_triggered], [pin_non_standard])
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)
                """,
                row.attemptedAt(), row.method().name(), row.identifier(), row.userId(), row.clientIp(), row.remoteAddr(),
                row.forwardedFor(), row.userAgent(), row.outcome().name(), row.wouldBlock(), row.ruleTriggered(),
                row.pinNonStandard());
        if (successUserId != null) {
            markSuccess(successUserId, row.method(), row.attemptedAt());
        }
    }

    /** Sets last_success_at in one statement (insert or update), which resets the failure counts. */
    public void markSuccess(Long userId, LoginMethod scope, LocalDateTime at) {
        jdbc.update("""
                MERGE [user_auth_state] WITH (HOLDLOCK) AS t
                USING (SELECT ? AS [user_id], ? AS [scope]) AS s
                    ON t.[user_id] = s.[user_id] AND t.[scope] = s.[scope]
                WHEN MATCHED THEN UPDATE SET t.[last_success_at] = ?
                WHEN NOT MATCHED THEN INSERT ([user_id], [scope], [last_success_at]) VALUES (s.[user_id], s.[scope], ?);
                """, userId, scope.name(), at, at);
    }

    /**
     * Enforce mode (Phase 2) only: never called while mode is monitor. Applies the next lock in one
     * conditional statement: level 1 after a success or after {@code escalationReset} without a
     * lock, otherwise one level up, capped at 3 (admin unlock, locked_until = 9999-12-31). Does
     * nothing while a lock is still running. Returns the new level, or 0 when nothing changed.
     */
    public int applyLock(Long userId, LoginMethod scope, LocalDateTime now, Duration escalationReset,
                         List<Duration> lockDurations, String reason) {
        var resetBefore = now.minus(escalationReset);
        var first = lockDurations.get(0).toSeconds();
        var second = lockDurations.get(1).toSeconds();
        var updated = jdbc.update("""
                UPDATE t SET
                    t.[lock_level] = n.[lvl],
                    t.[locked_until] = CASE n.[lvl] WHEN 1 THEN DATEADD(SECOND, ?, ?) WHEN 2 THEN DATEADD(SECOND, ?, ?)
                                        ELSE CAST('%s' AS DATETIME2) END,
                    t.[locked_at] = ?, t.[lock_reason] = ?, t.[unlocked_by] = NULL, t.[unlocked_at] = NULL
                FROM [user_auth_state] t
                CROSS APPLY (SELECT CASE
                    WHEN t.[locked_at] IS NULL OR t.[locked_at] <= ?
                         OR (t.[last_success_at] IS NOT NULL AND t.[last_success_at] > t.[locked_at]) THEN 1
                    WHEN t.[lock_level] >= 3 THEN 3
                    ELSE t.[lock_level] + 1 END AS [lvl]) n
                WHERE t.[user_id] = ? AND t.[scope] = ? AND (t.[locked_until] IS NULL OR t.[locked_until] <= ?)
                """.formatted(ADMIN_UNLOCK_UNTIL),
                first, now, second, now, now, reason, resetBefore, userId, scope.name(), now);
        if (updated > 0) {
            return lockLevel(userId, scope);
        }
        try {
            var inserted = jdbc.update("""
                    INSERT INTO [user_auth_state] ([user_id], [scope], [lock_level], [locked_until], [locked_at], [lock_reason])
                    SELECT ?, ?, 1, DATEADD(SECOND, ?, ?), ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM [user_auth_state] WHERE [user_id] = ? AND [scope] = ?)
                    """, userId, scope.name(), first, now, now, reason, userId, scope.name());
            return inserted > 0 ? 1 : 0;
        } catch (DuplicateKeyException race) {
            return 0;
        }
    }

    public int lockLevel(Long userId, LoginMethod scope) {
        List<Integer> rows = jdbc.queryForList("SELECT [lock_level] FROM [user_auth_state] WHERE [user_id] = ? AND [scope] = ?",
                Integer.class, userId, scope.name());
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /** Deletes up to {@code batchSize} rows older than {@code before}; returns how many were deleted. */
    public int deleteAttemptsBefore(LocalDateTime before, int batchSize) {
        return jdbc.update("DELETE TOP (?) FROM [login_attempts] WHERE [attempted_at] < ?", batchSize, before);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
