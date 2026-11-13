package com.beaconculinary.api.devices;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for devices and device_credentials (V105). Times are UTC LocalDateTime values. IDs are
 * UUID strings in lower case (SQL Server returns UNIQUEIDENTIFIER in upper case, so they're
 * normalised on read). Runs in the caller's transaction.
 */
@Repository
public class DeviceStore {
    private final JdbcTemplate jdbc;

    public DeviceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DeviceRow(String id, String name, String type, String serialNumber, String assetTag, String status,
                            String registeredByName, LocalDateTime registeredAt, String revokedByName,
                            LocalDateTime revokedAt, String revokeReason) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record CredentialRow(String id, String deviceId, String browserLabel, String status, String createdByName,
                                LocalDateTime createdAt, LocalDateTime lastSeenAt, String lastSeenIp,
                                String revokedByName, LocalDateTime revokedAt, String revokeReason) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }

        /** When this browser was last known to be in use (creation if never seen). */
        public LocalDateTime lastUsed() {
            return lastSeenAt != null ? lastSeenAt : createdAt;
        }
    }

    /** A credential looked up by token hash, with its device's status. */
    public record Resolved(String credentialId, String deviceId, String deviceName, String credentialStatus,
                           String deviceStatus, LocalDateTime lastUsed, byte[] tokenHash) {
    }

    private static final RowMapper<DeviceRow> DEVICE = (rs, i) -> new DeviceRow(
            id(rs.getString("id")), rs.getString("name"), rs.getString("device_type"), rs.getString("serial_number"),
            rs.getString("asset_tag"), rs.getString("status"), rs.getString("registered_by_name"),
            rs.getObject("registered_at", LocalDateTime.class), rs.getString("revoked_by_name"),
            rs.getObject("revoked_at", LocalDateTime.class), rs.getString("revoke_reason"));

    private static final RowMapper<CredentialRow> CREDENTIAL = (rs, i) -> new CredentialRow(
            id(rs.getString("id")), id(rs.getString("device_id")), rs.getString("browser_label"), rs.getString("status"),
            rs.getString("created_by_name"), rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("last_seen_at", LocalDateTime.class), rs.getString("last_seen_ip"),
            rs.getString("revoked_by_name"), rs.getObject("revoked_at", LocalDateTime.class), rs.getString("revoke_reason"));

    private static final String DEVICE_COLUMNS = "[id], [name], [device_type], [serial_number], [asset_tag], [status], "
            + "[registered_by_name], [registered_at], [revoked_by_name], [revoked_at], [revoke_reason]";
    private static final String CREDENTIAL_COLUMNS = "[id], [device_id], [browser_label], [status], [created_by_name], "
            + "[created_at], [last_seen_at], [last_seen_ip], [revoked_by_name], [revoked_at], [revoke_reason]";

    static String id(String raw) {
        return raw == null ? null : raw.toLowerCase(Locale.ROOT);
    }

    // ---- reads ----

    public Optional<Resolved> findByTokenHash(byte[] hash) {
        return jdbc.query("""
                SELECT c.[id], c.[device_id], d.[name], c.[status], d.[status], COALESCE(c.[last_seen_at], c.[created_at]), c.[token_hash]
                FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id]
                WHERE c.[token_hash] = ?
                """, (rs, i) -> new Resolved(id(rs.getString(1)), id(rs.getString(2)), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, LocalDateTime.class), rs.getBytes(7)),
                (Object) hash).stream().findFirst();
    }

    public Optional<DeviceRow> findDevice(String id) {
        return jdbc.query("SELECT " + DEVICE_COLUMNS + " FROM [devices] WHERE [id] = ?", DEVICE, id).stream().findFirst();
    }

    public Optional<DeviceRow> findActiveBySerial(String serial) {
        if (serial == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + DEVICE_COLUMNS + " FROM [devices] WHERE [status] = 'ACTIVE' AND [serial_number] = ?",
                DEVICE, serial).stream().findFirst();
    }

    public Optional<DeviceRow> findActiveByAssetTag(String assetTag) {
        if (assetTag == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + DEVICE_COLUMNS + " FROM [devices] WHERE [status] = 'ACTIVE' AND [asset_tag] = ?",
                DEVICE, assetTag).stream().findFirst();
    }

    public List<DeviceRow> listDevices() {
        return jdbc.query("SELECT " + DEVICE_COLUMNS + " FROM [devices] ORDER BY [registered_at] DESC, [name]", DEVICE);
    }

    public List<CredentialRow> listCredentials() {
        return jdbc.query("SELECT " + CREDENTIAL_COLUMNS + " FROM [device_credentials] ORDER BY [created_at]", CREDENTIAL);
    }

    public List<CredentialRow> credentialsOf(String deviceId) {
        return jdbc.query("SELECT " + CREDENTIAL_COLUMNS + " FROM [device_credentials] WHERE [device_id] = ? ORDER BY [created_at]",
                CREDENTIAL, deviceId);
    }

    public Optional<CredentialRow> findCredential(String deviceId, String credentialId) {
        return jdbc.query("SELECT " + CREDENTIAL_COLUMNS + " FROM [device_credentials] WHERE [id] = ? AND [device_id] = ?",
                CREDENTIAL, credentialId, deviceId).stream().findFirst();
    }

    /** Active browsers of a till that have been used within the idle period. */
    public int countUsableCredentials(String deviceId, LocalDateTime notUsedBefore) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM [device_credentials]
                WHERE [device_id] = ? AND [status] = 'ACTIVE' AND COALESCE([last_seen_at], [created_at]) >= ?
                """, Integer.class, deviceId, notUsedBefore);
        return count == null ? 0 : count;
    }

    // ---- writes ----

    public String insertDevice(String name, String serial, String assetTag, long by, String byName, LocalDateTime now) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO [devices] ([id], [name], [device_type], [serial_number], [asset_tag], [status],
                    [registered_by], [registered_by_name], [registered_at])
                VALUES (?, ?, 'POS', ?, ?, 'ACTIVE', ?, ?, ?)
                """, id, name, serial, assetTag, by, byName, now);
        return id;
    }

    public String insertCredential(String deviceId, byte[] tokenHash, String browserLabel, long by, String byName,
                                   String ip, LocalDateTime now) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO [device_credentials] ([id], [device_id], [token_hash], [browser_label], [status],
                    [created_by], [created_by_name], [created_at], [last_seen_at], [last_seen_ip])
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
                """, id, deviceId, tokenHash, browserLabel, by, byName, now, now, ip);
        return id;
    }

    /**
     * Updates last seen in one conditional statement, only when the previous update is at least
     * {@code interval} old. Returns true when it wrote (and the cookie should be renewed).
     */
    public boolean touch(String credentialId, LocalDateTime now, LocalDateTime notSeenSince, String ip) {
        return jdbc.update("""
                UPDATE [device_credentials] SET [last_seen_at] = ?, [last_seen_ip] = ?
                WHERE [id] = ? AND [status] = 'ACTIVE' AND ([last_seen_at] IS NULL OR [last_seen_at] <= ?)
                """, now, ip, credentialId, notSeenSince) > 0;
    }

    public void updateDevice(String id, String name, String serial, String assetTag) {
        jdbc.update("UPDATE [devices] SET [name] = ?, [serial_number] = ?, [asset_tag] = ? WHERE [id] = ?",
                name, serial, assetTag, id);
    }

    public void updateBrowserLabel(String credentialId, String label) {
        jdbc.update("UPDATE [device_credentials] SET [browser_label] = ? WHERE [id] = ?", label, credentialId);
    }

    /** Revokes the till and all its active browsers. Returns false when it was already revoked. */
    public boolean revokeDevice(String id, long by, String byName, String reason, LocalDateTime now) {
        var updated = jdbc.update("""
                UPDATE [devices] SET [status] = 'REVOKED', [revoked_by] = ?, [revoked_by_name] = ?, [revoked_at] = ?, [revoke_reason] = ?
                WHERE [id] = ? AND [status] = 'ACTIVE'
                """, by, byName, now, reason, id);
        jdbc.update("""
                UPDATE [device_credentials] SET [status] = 'REVOKED', [revoked_by] = ?, [revoked_by_name] = ?, [revoked_at] = ?, [revoke_reason] = ?
                WHERE [device_id] = ? AND [status] = 'ACTIVE'
                """, by, byName, now, reason, id);
        return updated > 0;
    }

    /** Revokes one browser. Returns false when it was already revoked. */
    public boolean revokeCredential(String credentialId, long by, String byName, String reason, LocalDateTime now) {
        return jdbc.update("""
                UPDATE [device_credentials] SET [status] = 'REVOKED', [revoked_by] = ?, [revoked_by_name] = ?, [revoked_at] = ?, [revoke_reason] = ?
                WHERE [id] = ? AND [status] = 'ACTIVE'
                """, by, byName, now, reason, credentialId) > 0;
    }
}
