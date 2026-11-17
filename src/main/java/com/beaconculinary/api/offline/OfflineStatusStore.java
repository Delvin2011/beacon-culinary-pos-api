package com.beaconculinary.api.offline;

import com.beaconculinary.api.offline.OfflineStatusDtos.PendingEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * O3: SQL for offline_till_status and offline_till_pending (V110): each till's latest status report
 * and its unsynced sales per shift and trading day. A report replaces the previous one, pending list
 * included. Times are UTC LocalDateTime values. Runs in the caller's transaction.
 */
@Repository
public class OfflineStatusStore {
    private final JdbcTemplate jdbc;

    public OfflineStatusStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StatusRow(UUID deviceId, int pendingCount, LocalDateTime oldestPendingSoldAt, LocalDateTime lastSyncAt,
                            LocalDateTime offlineSince, LocalDateTime tillClockUtc, LocalDateTime receivedAt,
                            long clockSkewMs, String appVersion) {
    }

    /** A till's pending sales for one shift and trading day, with when the till last reported. */
    public record PendingRow(UUID deviceId, String tillCode, String tillName, long shiftId, LocalDate tradingDay,
                             int pendingCount, LocalDateTime receivedAt) {
    }

    private static final RowMapper<StatusRow> STATUS = (rs, i) -> new StatusRow(
            uuid(rs.getString("device_id")), rs.getInt("pending_count"),
            rs.getObject("oldest_pending_sold_at", LocalDateTime.class), rs.getObject("last_sync_at", LocalDateTime.class),
            rs.getObject("offline_since", LocalDateTime.class), rs.getObject("till_clock_utc", LocalDateTime.class),
            rs.getObject("received_at", LocalDateTime.class), rs.getLong("clock_skew_ms"), rs.getString("app_version"));

    private static final RowMapper<PendingRow> PENDING = (rs, i) -> new PendingRow(
            uuid(rs.getString("device_id")), rs.getString("till_code"), rs.getString("name"), rs.getLong("shift_id"),
            rs.getObject("trading_day", LocalDate.class), rs.getInt("pending_count"),
            rs.getObject("received_at", LocalDateTime.class));

    public void save(StatusRow status, UUID credentialId, List<PendingEntry> pending) {
        jdbc.update("""
                MERGE [offline_till_status] WITH (HOLDLOCK) AS [t]
                USING (SELECT ? AS [device_id]) AS [s] ON [t].[device_id] = [s].[device_id]
                WHEN MATCHED THEN UPDATE SET
                    [credential_id] = ?, [pending_count] = ?, [oldest_pending_sold_at] = ?, [last_sync_at] = ?,
                    [offline_since] = ?, [till_clock_utc] = ?, [received_at] = ?, [clock_skew_ms] = ?, [app_version] = ?
                WHEN NOT MATCHED THEN INSERT ([device_id], [credential_id], [pending_count], [oldest_pending_sold_at],
                    [last_sync_at], [offline_since], [till_clock_utc], [received_at], [clock_skew_ms], [app_version])
                    VALUES ([s].[device_id], ?, ?, ?, ?, ?, ?, ?, ?, ?);""",
                status.deviceId().toString(),
                credentialId.toString(), status.pendingCount(), status.oldestPendingSoldAt(), status.lastSyncAt(),
                status.offlineSince(), status.tillClockUtc(), status.receivedAt(), status.clockSkewMs(), status.appVersion(),
                credentialId.toString(), status.pendingCount(), status.oldestPendingSoldAt(), status.lastSyncAt(),
                status.offlineSince(), status.tillClockUtc(), status.receivedAt(), status.clockSkewMs(), status.appVersion());
        jdbc.update("DELETE FROM [offline_till_pending] WHERE [device_id] = ?", status.deviceId().toString());
        for (var entry : pending) {
            jdbc.update("""
                    INSERT INTO [offline_till_pending] ([device_id], [shift_id], [trading_day], [pending_count], [oldest_sold_at])
                    VALUES (?, ?, ?, ?, ?)""",
                    status.deviceId().toString(), entry.shiftId(), java.sql.Date.valueOf(entry.tradingDay()), entry.count(),
                    entry.oldestSoldAt() == null ? null : com.beaconculinary.api.common.UtcTimestamps.toUtc(entry.oldestSoldAt()));
        }
    }

    public Optional<StatusRow> find(UUID deviceId) {
        return jdbc.query("SELECT * FROM [offline_till_status] WHERE [device_id] = ?", STATUS, deviceId.toString())
                .stream().findFirst();
    }

    public List<StatusRow> findAll() {
        return jdbc.query("SELECT * FROM [offline_till_status]", STATUS);
    }

    /** Every till's reported pending sales for a trading day (any till, active or not). */
    public List<PendingRow> pendingForDay(LocalDate tradingDay) {
        return jdbc.query("""
                SELECT p.[device_id], d.[till_code], d.[name], p.[shift_id], p.[trading_day], p.[pending_count], s.[received_at]
                FROM [offline_till_pending] p
                JOIN [offline_till_status] s ON s.[device_id] = p.[device_id]
                JOIN [devices] d ON d.[id] = p.[device_id]
                WHERE p.[trading_day] = ?
                ORDER BY d.[till_code], d.[name]""", PENDING, java.sql.Date.valueOf(tradingDay));
    }

    /** The reported pending sales of one shift, over all its trading days. */
    public List<PendingRow> pendingForShift(long shiftId) {
        return jdbc.query("""
                SELECT p.[device_id], d.[till_code], d.[name], p.[shift_id], p.[trading_day], p.[pending_count], s.[received_at]
                FROM [offline_till_pending] p
                JOIN [offline_till_status] s ON s.[device_id] = p.[device_id]
                JOIN [devices] d ON d.[id] = p.[device_id]
                WHERE p.[shift_id] = ?""", PENDING, shiftId);
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value.toLowerCase(Locale.ROOT));
    }
}
