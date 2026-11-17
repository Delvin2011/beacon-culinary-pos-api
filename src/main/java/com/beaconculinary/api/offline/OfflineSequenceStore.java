package com.beaconculinary.api.offline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * O3: SQL for offline_sequences (V110), the highest offline sale number the server has seen per
 * till and trading day (contract section 7). O4 calls {@link #recordSeen} as it accepts offline
 * sales; the grant returns {@link #nextFor} as nextOfflineSeq.
 */
@Repository
public class OfflineSequenceStore {
    private final JdbcTemplate jdbc;

    public OfflineSequenceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The highest number seen for this till and day, plus 1; 1 when none has been seen. */
    public int nextFor(UUID deviceId, LocalDate tradingDay) {
        var last = jdbc.query("SELECT [last_seq] FROM [offline_sequences] WHERE [device_id] = ? AND [trading_day] = ?",
                (rs, i) -> rs.getInt(1), deviceId.toString(), java.sql.Date.valueOf(tradingDay));
        return last.isEmpty() ? 1 : last.get(0) + 1;
    }

    /** Raises the till's highest number for the day to {@code seq}; a lower number changes nothing. */
    public void recordSeen(UUID deviceId, LocalDate tradingDay, int seq, LocalDateTime now) {
        jdbc.update("""
                MERGE [offline_sequences] WITH (HOLDLOCK) AS [t]
                USING (SELECT ? AS [device_id], ? AS [trading_day], ? AS [seq]) AS [s]
                    ON [t].[device_id] = [s].[device_id] AND [t].[trading_day] = [s].[trading_day]
                WHEN MATCHED AND [s].[seq] > [t].[last_seq] THEN
                    UPDATE SET [last_seq] = [s].[seq], [updated_at] = ?
                WHEN NOT MATCHED THEN
                    INSERT ([device_id], [trading_day], [last_seq], [updated_at]) VALUES ([s].[device_id], [s].[trading_day], [s].[seq], ?);""",
                deviceId.toString(), java.sql.Date.valueOf(tradingDay), seq, now, now);
    }
}
