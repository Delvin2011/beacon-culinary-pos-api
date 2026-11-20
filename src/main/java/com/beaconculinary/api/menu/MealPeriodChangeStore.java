package com.beaconculinary.api.menu;

import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.menu.MealPeriodAdminDtos.HistoryEntry;
import com.beaconculinary.api.menu.MealPeriodAdminDtos.LastChange;
import com.beaconculinary.api.menu.MealPeriodAdminDtos.Values;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T0-5a: SQL for meal_period_changes (V114, insert-only), the meal period write lock, and the plan
 * items that keep a period in use. Times are UTC (application clock).
 */
@Repository
public class MealPeriodChangeStore {
    private final JdbcTemplate jdbc;

    public MealPeriodChangeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serialises meal period writes for the rest of the transaction, so two admins can't create an overlap or a
     * duplicate name together. */
    public void lockForWrite() {
        jdbc.execute("EXEC sp_getapplock @Resource = 'meal_periods', @LockMode = 'Exclusive', @LockOwner = 'Transaction', @LockTimeout = 10000");
    }

    public void insert(long periodId, String action, Values before, Values after, LocalDateTime at, long byId, String byName) {
        jdbc.update("""
                INSERT INTO [meal_period_changes] ([meal_period_id], [action],
                    [old_name], [old_start_time], [old_end_time], [old_sort_order], [old_active],
                    [new_name], [new_start_time], [new_end_time], [new_sort_order], [new_active],
                    [changed_at], [changed_by], [changed_by_name])
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                periodId, action,
                before == null ? null : before.name(), before == null ? null : before.startTime(),
                before == null ? null : before.endTime(), before == null ? null : before.sortOrder(),
                before == null ? null : before.active(),
                after.name(), after.startTime(), after.endTime(), after.sortOrder(), after.active(),
                at, byId, byName);
    }

    /** The newest change of each period that has one. */
    public Map<Long, LastChange> lastChanges() {
        var result = new HashMap<Long, LastChange>();
        jdbc.query("""
                SELECT [meal_period_id], [action], [changed_by_name], [changed_at] FROM (
                    SELECT *, ROW_NUMBER() OVER (PARTITION BY [meal_period_id] ORDER BY [changed_at] DESC, [id] DESC) AS [rn]
                    FROM [meal_period_changes]) newest
                WHERE [rn] = 1""",
                rs -> {
                    result.put(rs.getLong("meal_period_id"), new LastChange(rs.getString("action"),
                            rs.getString("changed_by_name"), UtcTimestamps.toInstant(rs.getObject("changed_at", LocalDateTime.class))));
                });
        return result;
    }

    /** Newest first; periodId null for every period. */
    public List<HistoryEntry> history(Long periodId, int page, int size) {
        var where = periodId == null ? "" : "WHERE [meal_period_id] = ? ";
        var sql = "SELECT * FROM [meal_period_changes] " + where
                + "ORDER BY [changed_at] DESC, [id] DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        var args = periodId == null ? new Object[]{(long) page * size, size} : new Object[]{periodId, (long) page * size, size};
        return jdbc.query(sql, (rs, i) -> entry(rs), args);
    }

    public long countHistory(Long periodId) {
        return periodId == null
                ? jdbc.queryForObject("SELECT COUNT_BIG(*) FROM [meal_period_changes]", Long.class)
                : jdbc.queryForObject("SELECT COUNT_BIG(*) FROM [meal_period_changes] WHERE [meal_period_id] = ?", Long.class, periodId);
    }

    /** A period's values at a time (UTC): after the last change at or before it; else before the first change
     * after it; empty with no history, or when the period was created after it. */
    public Optional<Values> valuesAt(long periodId, LocalDateTime at) {
        var before = jdbc.query("""
                SELECT TOP 1 * FROM [meal_period_changes] WHERE [meal_period_id] = ? AND [changed_at] <= ?
                ORDER BY [changed_at] DESC, [id] DESC""", (rs, i) -> entry(rs), periodId, at);
        if (!before.isEmpty()) {
            return Optional.of(before.get(0).after());
        }
        var after = jdbc.query("""
                SELECT TOP 1 * FROM [meal_period_changes] WHERE [meal_period_id] = ? AND [changed_at] > ?
                ORDER BY [changed_at], [id]""", (rs, i) -> entry(rs), periodId, at);
        return after.isEmpty() ? Optional.empty() : Optional.ofNullable(after.get(0).before());
    }

    /** Options and extras of a period dated on or after a day: what keeps it from being deactivated. */
    public record InUse(int options, int extras, LocalDate fromDate) {
        public boolean any() {
            return options + extras > 0;
        }
    }

    public InUse inUseFrom(long periodId, LocalDate day) {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM [daily_meal_options] WHERE [meal_period_id] = ? AND [option_date] >= ?) AS [options],
                    (SELECT COUNT(*) FROM [daily_component_stock] WHERE [meal_period_id] = ? AND [option_date] >= ?) AS [extras],
                    (SELECT MIN([d]) FROM (
                        SELECT [option_date] AS [d] FROM [daily_meal_options] WHERE [meal_period_id] = ? AND [option_date] >= ?
                        UNION ALL
                        SELECT [option_date] FROM [daily_component_stock] WHERE [meal_period_id] = ? AND [option_date] >= ?) items) AS [from_date]""",
                (rs, i) -> new InUse(rs.getInt("options"), rs.getInt("extras"), rs.getObject("from_date", LocalDate.class)),
                periodId, day, periodId, day, periodId, day, periodId, day);
    }

    private static HistoryEntry entry(ResultSet rs) throws SQLException {
        var hasBefore = rs.getString("old_name") != null;
        var before = hasBefore ? new Values(rs.getString("old_name"), rs.getObject("old_start_time", LocalTime.class),
                rs.getObject("old_end_time", LocalTime.class), rs.getInt("old_sort_order"), rs.getBoolean("old_active")) : null;
        var after = new Values(rs.getString("new_name"), rs.getObject("new_start_time", LocalTime.class),
                rs.getObject("new_end_time", LocalTime.class), rs.getInt("new_sort_order"), rs.getBoolean("new_active"));
        return new HistoryEntry(rs.getLong("id"), rs.getLong("meal_period_id"), rs.getString("action"), before, after,
                UtcTimestamps.toInstant(rs.getObject("changed_at", LocalDateTime.class)), rs.getLong("changed_by"),
                rs.getString("changed_by_name"));
    }
}
