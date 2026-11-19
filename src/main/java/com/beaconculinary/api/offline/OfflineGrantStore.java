package com.beaconculinary.api.offline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * O3: SQL for offline_grants (V110). Times are UTC LocalDateTime values. Runs in the caller's
 * transaction. Revoking never deletes: revoked grants stay readable for O4's sync. Each revoke
 * touches only grants that are still active, so it can run any number of times.
 */
@Repository
public class OfflineGrantStore {
    private final JdbcTemplate jdbc;

    public OfflineGrantStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum RevokeReason {
        REPLACED, SHIFT_CLOSED, SHIFT_FORCE_CLOSED, TILL_REVOKED, BROWSER_REVOKED, CASHIER_DEACTIVATED
    }

    public record GrantRow(UUID id, UUID deviceId, UUID credentialId, long shiftId, long cashierId, String cashierName,
                           UUID sessionId, LocalDateTime issuedAt, LocalDateTime expiresAt, LocalDateTime revokedAt,
                           RevokeReason revokeReason, UUID replacedBy) {
        public boolean active() {
            return revokedAt == null;
        }
    }

    private static final RowMapper<GrantRow> GRANT = (rs, i) -> new GrantRow(
            uuid(rs.getString("id")), uuid(rs.getString("device_id")), uuid(rs.getString("credential_id")),
            rs.getLong("shift_id"), rs.getLong("cashier_id"), rs.getString("cashier_name"), uuid(rs.getString("session_id")),
            rs.getObject("issued_at", LocalDateTime.class), rs.getObject("expires_at", LocalDateTime.class),
            rs.getObject("revoked_at", LocalDateTime.class),
            rs.getString("revoke_reason") == null ? null : RevokeReason.valueOf(rs.getString("revoke_reason")),
            uuid(rs.getString("replaced_by")));

    public void insert(GrantRow grant) {
        jdbc.update("""
                INSERT INTO [offline_grants] ([id], [device_id], [credential_id], [shift_id], [cashier_id], [cashier_name],
                    [session_id], [issued_at], [expires_at])
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                grant.id().toString(), grant.deviceId().toString(), grant.credentialId().toString(), grant.shiftId(),
                grant.cashierId(), grant.cashierName(), grant.sessionId().toString(), grant.issuedAt(), grant.expiresAt());
    }

    public Optional<GrantRow> find(UUID id) {
        return jdbc.query("SELECT * FROM [offline_grants] WHERE [id] = ?", GRANT, id.toString()).stream().findFirst();
    }

    /** The active grant of this till and shift is replaced by {@code replacedBy}. */
    public int replaceActive(UUID deviceId, long shiftId, UUID replacedBy, LocalDateTime now) {
        return jdbc.update("""
                UPDATE [offline_grants] SET [revoked_at] = ?, [revoke_reason] = 'REPLACED', [replaced_by] = ?
                WHERE [device_id] = ? AND [shift_id] = ? AND [revoked_at] IS NULL""",
                now, replacedBy.toString(), deviceId.toString(), shiftId);
    }

    public int revokeForShift(long shiftId, RevokeReason reason, LocalDateTime now) {
        return revoke("[shift_id] = ?", shiftId, reason, now);
    }

    public int revokeForDevice(UUID deviceId, RevokeReason reason, LocalDateTime now) {
        return revoke("[device_id] = ?", deviceId.toString(), reason, now);
    }

    public int revokeForCredential(UUID credentialId, RevokeReason reason, LocalDateTime now) {
        return revoke("[credential_id] = ?", credentialId.toString(), reason, now);
    }

    public int revokeForCashier(long cashierId, RevokeReason reason, LocalDateTime now) {
        return revoke("[cashier_id] = ?", cashierId, reason, now);
    }

    private int revoke(String condition, Object value, RevokeReason reason, LocalDateTime now) {
        return jdbc.update("UPDATE [offline_grants] SET [revoked_at] = ?, [revoke_reason] = ? WHERE " + condition
                + " AND [revoked_at] IS NULL", now, reason.name(), value);
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value.toLowerCase(Locale.ROOT));
    }
}
