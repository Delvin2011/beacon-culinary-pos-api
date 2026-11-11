package com.beaconculinary.api.auth.protection;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * SQL for login_attempts and user_auth_state (V103, V104). All times are UTC LocalDateTime values.
 * Failures are the outcomes BAD_CREDENTIAL, UNKNOWN_USER and DISABLED.
 */
@Repository
public class LoginProtectionStore {
    static final String ADMIN_UNLOCK_UNTIL = "9999-12-31T23:59:59.9999999";
    /** The locked_until written for a lock that needs an admin. */
    public static final LocalDateTime ADMIN_UNLOCK = LocalDateTime.parse(ADMIN_UNLOCK_UNTIL);
    private static final String FAILURE = "[outcome] IN ('BAD_CREDENTIAL', 'UNKNOWN_USER', 'DISABLED')";

    private final JdbcTemplate jdbc;

    public LoginProtectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One login_attempts row. Strings must already fit their columns. */
    public record AttemptRow(LocalDateTime attemptedAt, LoginMethod method, String identifier, Long userId,
                             String clientIp, String remoteAddr, String forwardedFor, String userAgent,
                             LoginOutcome outcome, boolean wouldBlock, String ruleTriggered, Boolean pinNonStandard,
                             Integer lockLevel, LocalDateTime lockedUntil) {

        /** A row that triggered no lock. */
        public AttemptRow(LocalDateTime attemptedAt, LoginMethod method, String identifier, Long userId,
                          String clientIp, String remoteAddr, String forwardedFor, String userAgent,
                          LoginOutcome outcome, boolean wouldBlock, String ruleTriggered, Boolean pinNonStandard) {
            this(attemptedAt, method, identifier, userId, clientIp, remoteAddr, forwardedFor, userAgent,
                    outcome, wouldBlock, ruleTriggered, pinNonStandard, null, null);
        }

        AttemptRow withLock(int level, LocalDateTime until) {
            return new AttemptRow(attemptedAt, method, identifier, userId, clientIp, remoteAddr, forwardedFor, userAgent,
                    outcome, wouldBlock, ruleTriggered, pinNonStandard, level, until);
        }
    }

    /** A user's user_auth_state row for one scope. */
    public record AuthState(int lockLevel, LocalDateTime lockedUntil, LocalDateTime lockedAt,
                            LocalDateTime unlockedAt, LocalDateTime lastSuccessAt) {
        public boolean lockedAt(LocalDateTime now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }
    }

    /** A lock recorded on a login_attempts row (V104): the lock state of an unknown identifier. */
    public record HistoryLock(int level, LocalDateTime lockedUntil, LocalDateTime lockedAt) {
        public boolean lockedAt(LocalDateTime now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }
    }

    /** An active account lock, for the staff page. */
    public record ActiveLock(long userId, LoginMethod scope, int level, LocalDateTime lockedUntil) {
    }

    // ---- failure counts ----

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
     * For a window-based block: the time of the failure whose expiry brings the count below the
     * threshold, that is the failure at position {@code offset} (0 = oldest) after {@code after}.
     * The block ends at that time plus the window.
     */
    public Optional<LocalDateTime> failureAtByIdentifierAndIp(LoginMethod method, String identifier, String clientIp,
                                                              LocalDateTime after, long offset) {
        if (identifier == null) {
            return Optional.empty();
        }
        if (clientIp == null) {
            return firstTime("SELECT [attempted_at] FROM [login_attempts] WHERE [method] = ? AND [identifier] = ? AND [client_ip] IS NULL AND [attempted_at] > ? AND "
                    + FAILURE + " ORDER BY [attempted_at], [id] OFFSET ? ROWS FETCH NEXT 1 ROWS ONLY", method.name(), identifier, after, offset);
        }
        return firstTime("SELECT [attempted_at] FROM [login_attempts] WHERE [method] = ? AND [identifier] = ? AND [client_ip] = ? AND [attempted_at] > ? AND "
                + FAILURE + " ORDER BY [attempted_at], [id] OFFSET ? ROWS FETCH NEXT 1 ROWS ONLY", method.name(), identifier, clientIp, after, offset);
    }

    /** As {@link #failureAtByIdentifierAndIp}, keyed by user. */
    public Optional<LocalDateTime> failureAtByUser(LoginMethod method, Long userId, LocalDateTime after, long offset) {
        if (userId == null) {
            return Optional.empty();
        }
        return firstTime("SELECT [attempted_at] FROM [login_attempts] WHERE [method] = ? AND [user_id] = ? AND [attempted_at] > ? AND "
                + FAILURE + " ORDER BY [attempted_at], [id] OFFSET ? ROWS FETCH NEXT 1 ROWS ONLY", method.name(), userId, after, offset);
    }

    // ---- monitor-mode escalation estimate ----

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
    public Optional<String> latestRule(LoginMethod method, String identifier, LocalDateTime after) {
        if (identifier == null) {
            return Optional.empty();
        }
        List<String> rows = jdbc.queryForList("""
                SELECT TOP 1 CASE WHEN [would_block] = 1 THEN [rule_triggered] END
                FROM [login_attempts]
                WHERE [method] = ? AND [identifier] = ? AND [attempted_at] > ?
                ORDER BY [attempted_at] DESC, [id] DESC
                """, String.class, method.name(), identifier, after);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    // ---- state ----

    public LocalDateTime lastSuccess(Long userId, LoginMethod scope) {
        if (userId == null) {
            return null;
        }
        List<LocalDateTime> rows = jdbc.queryForList(
                "SELECT [last_success_at] FROM [user_auth_state] WHERE [user_id] = ? AND [scope] = ?",
                LocalDateTime.class, userId, scope.name());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Optional<AuthState> state(Long userId, LoginMethod scope) {
        if (userId == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT [lock_level], [locked_until], [locked_at], [unlocked_at], [last_success_at]
                FROM [user_auth_state] WHERE [user_id] = ? AND [scope] = ?
                """, (rs, i) -> new AuthState(rs.getInt(1), rs.getObject(2, LocalDateTime.class),
                        rs.getObject(3, LocalDateTime.class), rs.getObject(4, LocalDateTime.class),
                        rs.getObject(5, LocalDateTime.class)),
                userId, scope.name()).stream().findFirst();
    }

    /** The latest lock recorded on an attempt for this identifier after {@code after} (unknown identifiers). */
    public Optional<HistoryLock> latestLock(LoginMethod method, String identifier, LocalDateTime after) {
        if (identifier == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT TOP 1 [lock_level], [locked_until], [attempted_at]
                FROM [login_attempts]
                WHERE [method] = ? AND [identifier] = ? AND [lock_level] IS NOT NULL AND [attempted_at] > ?
                ORDER BY [attempted_at] DESC, [id] DESC
                """, (rs, i) -> new HistoryLock(rs.getInt(1), rs.getObject(2, LocalDateTime.class),
                        rs.getObject(3, LocalDateTime.class)),
                method.name(), identifier, after).stream().findFirst();
    }

    /** Whether the user has an active account-level lock on PASSWORD or PIN sign-in. */
    public boolean isAccountLocked(Long userId, LocalDateTime now) {
        if (userId == null) {
            return false;
        }
        return count("SELECT COUNT(*) FROM [user_auth_state] WHERE [user_id] = ? AND [scope] IN ('PASSWORD', 'PIN') AND [locked_until] > ?",
                userId, now) > 0;
    }

    /** Active PASSWORD and PIN locks, for every user. */
    public List<ActiveLock> activeAccountLocks(LocalDateTime now) {
        return jdbc.query("""
                SELECT [user_id], [scope], [lock_level], [locked_until]
                FROM [user_auth_state]
                WHERE [scope] IN ('PASSWORD', 'PIN') AND [locked_until] > ?
                """, (rs, i) -> new ActiveLock(rs.getLong(1), LoginMethod.valueOf(rs.getString(2)), rs.getInt(3),
                        rs.getObject(4, LocalDateTime.class)), now);
    }

    // ---- writes ----

    /**
     * Writes the attempt and, for a success by a known user, its last_success_at, in a transaction
     * of its own: a failure is kept even when the caller's transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAttempt(AttemptRow row, Long successUserId) {
        insert(row);
        if (successUserId != null) {
            markSuccess(successUserId, row.method(), row.attemptedAt());
        }
    }

    /**
     * Enforce mode: applies the next account lock to a known user (user_auth_state) and writes the
     * failure that triggered it, with the lock copied onto the row, in a transaction of its own.
     * Returns the new level and end, or level 0 when a lock was already running.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HistoryLock saveAttemptWithAccountLock(AttemptRow row, Long userId, Duration escalationReset,
                                                  List<Duration> lockDurations) {
        var level = applyLock(userId, row.method(), row.attemptedAt(), escalationReset, lockDurations, row.ruleTriggered());
        var until = level > 0 ? state(userId, row.method()).map(AuthState::lockedUntil).orElse(null) : null;
        insert(level > 0 ? row.withLock(level, until) : row);
        return new HistoryLock(level, until, row.attemptedAt());
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
     * Enforce mode only. Applies the next lock in one conditional statement: level 1 after a
     * success, an unlock, or {@code escalationReset} without a lock; otherwise one level up, capped
     * at 3 (admin unlock, locked_until = 9999-12-31). Does nothing while a lock is still running.
     * Returns the new level, or 0 when nothing changed.
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
                         OR (t.[last_success_at] IS NOT NULL AND t.[last_success_at] > t.[locked_at])
                         OR (t.[unlocked_at] IS NOT NULL AND t.[unlocked_at] > t.[locked_at]) THEN 1
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

    /**
     * Ends any lock for these scopes and sets unlocked_at, so failures and window blocks before it no
     * longer count. Runs in the CALLER's transaction on purpose: staff management writes the users
     * row first, and a separate transaction inserting a user_auth_state row would then wait on it.
     * Returns how many of the scopes were locked.
     */
    public int unlock(Long userId, Collection<LoginMethod> scopes, Long unlockedBy, LocalDateTime now) {
        var wereLocked = 0;
        for (var scope : scopes) {
            wereLocked += count("SELECT COUNT(*) FROM [user_auth_state] WHERE [user_id] = ? AND [scope] = ? AND [locked_until] > ?",
                    userId, scope.name(), now) > 0 ? 1 : 0;
            jdbc.update("""
                    MERGE [user_auth_state] WITH (HOLDLOCK) AS t
                    USING (SELECT ? AS [user_id], ? AS [scope]) AS s
                        ON t.[user_id] = s.[user_id] AND t.[scope] = s.[scope]
                    WHEN MATCHED THEN UPDATE SET t.[lock_level] = 0, t.[locked_until] = NULL,
                        t.[unlocked_by] = ?, t.[unlocked_at] = ?
                    WHEN NOT MATCHED THEN INSERT ([user_id], [scope], [unlocked_by], [unlocked_at])
                        VALUES (s.[user_id], s.[scope], ?, ?);
                    """, userId, scope.name(), unlockedBy, now, unlockedBy, now);
        }
        return wereLocked;
    }

    /** Deletes up to {@code batchSize} rows older than {@code before}; returns how many were deleted. */
    public int deleteAttemptsBefore(LocalDateTime before, int batchSize) {
        return jdbc.update("DELETE TOP (?) FROM [login_attempts] WHERE [attempted_at] < ?", batchSize, before);
    }

    private void insert(AttemptRow row) {
        jdbc.update("""
                INSERT INTO [login_attempts] ([attempted_at], [method], [identifier], [user_id], [client_ip], [remote_addr],
                    [forwarded_for], [user_agent], [device_id], [outcome], [would_block], [rule_triggered], [pin_non_standard],
                    [lock_level], [locked_until])
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
                """,
                row.attemptedAt(), row.method().name(), row.identifier(), row.userId(), row.clientIp(), row.remoteAddr(),
                row.forwardedFor(), row.userAgent(), row.outcome().name(), row.wouldBlock(), row.ruleTriggered(),
                row.pinNonStandard(), row.lockLevel(), row.lockedUntil());
    }

    private Optional<LocalDateTime> firstTime(String sql, Object... args) {
        return jdbc.queryForList(sql, LocalDateTime.class, args).stream().findFirst();
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
