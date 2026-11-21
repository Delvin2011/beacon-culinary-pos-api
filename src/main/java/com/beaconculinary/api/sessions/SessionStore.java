package com.beaconculinary.api.sessions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for user_sessions (V107). Times are UTC LocalDateTime values from the application clock. Every
 * "end" statement only touches active rows (ended_at IS NULL), so ending twice changes nothing and
 * the first reason stays. Runs in the caller's transaction.
 */
@Repository
public class SessionStore {
    private final JdbcTemplate jdbc;

    public SessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SessionRow(UUID id, long userId, UUID deviceId, SessionKind kind, LocalDateTime startedAt,
                             LocalDateTime lastSeenAt, LocalDateTime endedAt, SessionEndReason endReason) {
        public boolean active() {
            return endedAt == null;
        }
    }

    private static final RowMapper<SessionRow> ROW = (rs, i) -> {
        var device = rs.getString("device_id");
        var reason = rs.getString("end_reason");
        return new SessionRow(UUID.fromString(rs.getString("id")), rs.getLong("user_id"),
                device == null ? null : UUID.fromString(device), SessionKind.valueOf(rs.getString("kind")),
                rs.getObject("started_at", LocalDateTime.class), rs.getObject("last_seen_at", LocalDateTime.class),
                rs.getObject("ended_at", LocalDateTime.class), reason == null ? null : SessionEndReason.valueOf(reason));
    };

    public void insert(UUID id, long userId, UUID deviceId, UUID credentialId, SessionKind kind, String method,
                       LocalDateTime now) {
        jdbc.update("""
                INSERT INTO [user_sessions] ([id], [user_id], [device_id], [credential_id], [kind], [method], [started_at], [last_seen_at])
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), userId, str(deviceId), str(credentialId), kind.name(), method, now, now);
    }

    public Optional<SessionRow> find(UUID id) {
        return jdbc.query("SELECT * FROM [user_sessions] WHERE [id] = ?", ROW, id.toString()).stream().findFirst();
    }

    /** Ends one session; false when it had already ended. */
    public boolean end(UUID id, SessionEndReason reason, LocalDateTime now) {
        return jdbc.update("UPDATE [user_sessions] SET [ended_at] = ?, [end_reason] = ? WHERE [id] = ? AND [ended_at] IS NULL",
                now, reason.name(), id.toString()) > 0;
    }

    /** R8: the user's active POS sessions, wherever they are. */
    public int endActivePosOfUser(long userId, SessionEndReason reason, LocalDateTime now) {
        return jdbc.update("""
                UPDATE [user_sessions] SET [ended_at] = ?, [end_reason] = ?
                WHERE [user_id] = ? AND [kind] = 'POS' AND [ended_at] IS NULL
                """, now, reason.name(), userId);
    }

    /** T0-6a: every active session of the user, whatever its kind (their credentials changed). */
    public int endAllOfUser(long userId, SessionEndReason reason, LocalDateTime now) {
        return jdbc.update("UPDATE [user_sessions] SET [ended_at] = ?, [end_reason] = ? WHERE [user_id] = ? AND [ended_at] IS NULL",
                now, reason.name(), userId);
    }

    /** C8: every active POS session on a till. */
    public int endActivePosOnDevice(UUID deviceId, SessionEndReason reason, LocalDateTime now) {
        return jdbc.update("""
                UPDATE [user_sessions] SET [ended_at] = ?, [end_reason] = ?
                WHERE [device_id] = ? AND [kind] = 'POS' AND [ended_at] IS NULL
                """, now, reason.name(), deviceId.toString());
    }

    /** A revoked till: every active session on it, whatever its kind. */
    public int endActiveOnDevice(UUID deviceId, SessionEndReason reason, LocalDateTime now) {
        return jdbc.update("UPDATE [user_sessions] SET [ended_at] = ?, [end_reason] = ? WHERE [device_id] = ? AND [ended_at] IS NULL",
                now, reason.name(), deviceId.toString());
    }

    /** A revoked browser: every active session started from it. */
    public int endActiveOnCredential(UUID credentialId, SessionEndReason reason, LocalDateTime now) {
        return jdbc.update("UPDATE [user_sessions] SET [ended_at] = ?, [end_reason] = ? WHERE [credential_id] = ? AND [ended_at] IS NULL",
                now, reason.name(), credentialId.toString());
    }

    /** R10: activity on an active session. */
    public void touch(UUID id, LocalDateTime now) {
        jdbc.update("UPDATE [user_sessions] SET [last_seen_at] = ? WHERE [id] = ? AND [ended_at] IS NULL", now, id.toString());
    }

    /** R9/R12: an active POS session of the user on the till, seen since {@code seenSince}. */
    public boolean hasActivePosSession(long userId, UUID deviceId, LocalDateTime seenSince) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM [user_sessions]
                WHERE [user_id] = ? AND [device_id] = ? AND [kind] = 'POS' AND [ended_at] IS NULL AND [last_seen_at] >= ?
                """, Integer.class, userId, deviceId.toString(), seenSince);
        return count != null && count > 0;
    }

    /** R10 sweep: POS sessions not seen since {@code seenBefore}. */
    public int endIdlePos(LocalDateTime seenBefore, LocalDateTime now) {
        return jdbc.update("""
                UPDATE [user_sessions] SET [ended_at] = ?, [end_reason] = 'IDLE'
                WHERE [ended_at] IS NULL AND [kind] = 'POS' AND [last_seen_at] < ?
                """, now, seenBefore);
    }

    /** Sweep: sessions started before {@code startedBefore} (their refresh token has expired). */
    public int endStartedBefore(LocalDateTime startedBefore, SessionEndReason reason, LocalDateTime now) {
        return jdbc.update("UPDATE [user_sessions] SET [ended_at] = ?, [end_reason] = ? WHERE [ended_at] IS NULL AND [started_at] < ?",
                now, reason.name(), startedBefore);
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }
}
