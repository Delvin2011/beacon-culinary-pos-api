package com.beaconculinary.api.inventory;

import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.inventory.LocationAdminDtos.HistoryEntry;
import com.beaconculinary.api.inventory.LocationAdminDtos.StockLine;
import com.beaconculinary.api.inventory.LocationAdminDtos.StockSummary;
import com.beaconculinary.api.inventory.LocationAdminDtos.Values;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * T0-5c: SQL for location_changes (V115, insert-only), the location write lock, and what keeps a
 * location in use (stock on hand, open requests, open stock takes). Times are UTC (application clock).
 */
@Repository
public class LocationChangeStore {
    /** Most non-zero stock lines a deactivation refusal lists; the count is always complete. */
    static final int STOCK_LINES_SHOWN = 50;

    private final JdbcTemplate jdbc;

    public LocationChangeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serialises location writes for the rest of the transaction, so two admins can't pass the
     * maximum, reuse a name or deactivate an assigned location together. */
    public void lockForWrite() {
        jdbc.execute("EXEC sp_getapplock @Resource = 'locations', @LockMode = 'Exclusive', @LockOwner = 'Transaction', @LockTimeout = 10000");
    }

    public void insert(long locationId, String action, LocationRole role, Long previousLocationId, Values before,
                       Values after, LocalDateTime at, long byId, String byName) {
        jdbc.update("""
                INSERT INTO [location_changes] ([location_id], [action], [role], [old_location_id],
                    [old_name], [old_sort_order], [old_active], [new_name], [new_sort_order], [new_active],
                    [changed_at], [changed_by], [changed_by_name])
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                locationId, action, role == null ? null : role.name(), previousLocationId,
                before == null ? null : before.name(), before == null ? null : before.sortOrder(),
                before == null ? null : before.active(),
                after.name(), after.sortOrder(), after.active(), at, byId, byName);
    }

    public long countHistory(Long locationId) {
        return locationId == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM [location_changes]", Long.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM [location_changes] WHERE [location_id] = ?", Long.class, locationId);
    }

    /** Newest first. */
    public List<HistoryEntry> history(Long locationId, int page, int size) {
        var where = locationId == null ? "" : "WHERE [location_id] = ? ";
        var args = new ArrayList<Object>();
        if (locationId != null) {
            args.add(locationId);
        }
        args.add((long) page * size);
        args.add(size);
        return jdbc.query("SELECT * FROM [location_changes] " + where
                        + "ORDER BY [changed_at] DESC, [id] DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, n) -> historyEntry(rs), args.toArray());
    }

    /** Per location: ingredients whose stock there is not zero, and how many of those are negative. */
    public Map<Long, StockSummary> stockSummaries() {
        var result = new HashMap<Long, StockSummary>();
        jdbc.query("""
                SELECT [location_id], COUNT(*) AS [with_stock], SUM(CASE WHEN [qty] < 0 THEN 1 ELSE 0 END) AS [negative]
                FROM (SELECT [location_id], [ingredient_id], SUM([quantity]) AS [qty]
                      FROM [ingredient_stock_movements] GROUP BY [location_id], [ingredient_id]) per_ingredient
                WHERE [qty] <> 0
                GROUP BY [location_id]""",
                rs -> {
                    result.put(rs.getLong("location_id"), new StockSummary(rs.getInt("with_stock"), rs.getInt("negative")));
                });
        return result;
    }

    /** The location's non-zero stock figures, largest first, at most {@link #STOCK_LINES_SHOWN}. */
    public List<StockLine> stockOnHand(long locationId) {
        return jdbc.query("""
                SELECT TOP (?) i.[id], i.[name], i.[unit], per_ingredient.[qty]
                FROM (SELECT [ingredient_id], SUM([quantity]) AS [qty] FROM [ingredient_stock_movements]
                      WHERE [location_id] = ? GROUP BY [ingredient_id]) per_ingredient
                JOIN [ingredients] i ON i.[id] = per_ingredient.[ingredient_id]
                WHERE per_ingredient.[qty] <> 0
                ORDER BY ABS(per_ingredient.[qty]) DESC, i.[name]""",
                (rs, n) -> new StockLine(rs.getLong("id"), rs.getString("name"), unit(rs.getString("unit")),
                        rs.getBigDecimal("qty")),
                STOCK_LINES_SHOWN, locationId);
    }

    public int countStockOnHand(long locationId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT [ingredient_id] FROM [ingredient_stock_movements]
                      WHERE [location_id] = ? GROUP BY [ingredient_id] HAVING SUM([quantity]) <> 0) non_zero""",
                Integer.class, locationId);
    }

    /** Requests that can still move stock at this location: REQUESTED or PARTIALLY_ACTIONED. */
    public List<Long> openStockRequestIds(long locationId) {
        return jdbc.queryForList("""
                SELECT [id] FROM [stock_requests]
                WHERE [location_id] = ? AND [status] IN ('REQUESTED', 'PARTIALLY_ACTIONED') ORDER BY [id]""",
                Long.class, locationId);
    }

    /** Counts still waiting for review. */
    public List<Long> openStockTakeIds(long locationId) {
        return jdbc.queryForList("SELECT [id] FROM [stock_takes] WHERE [location_id] = ? AND [status] = 'SUBMITTED' ORDER BY [id]",
                Long.class, locationId);
    }

    private static HistoryEntry historyEntry(ResultSet rs) throws SQLException {
        var oldName = rs.getString("old_name");
        var before = oldName == null ? null
                : new Values(oldName, rs.getInt("old_sort_order"), rs.getBoolean("old_active"));
        var after = new Values(rs.getString("new_name"), rs.getInt("new_sort_order"), rs.getBoolean("new_active"));
        var role = rs.getString("role");
        var previous = rs.getObject("old_location_id");
        return new HistoryEntry(rs.getLong("id"), rs.getLong("location_id"), rs.getString("action"),
                role == null ? null : LocationRole.valueOf(role), previous == null ? null : ((Number) previous).longValue(),
                before, after, UtcTimestamps.toInstant(rs.getObject("changed_at", LocalDateTime.class)),
                rs.getLong("changed_by"), rs.getString("changed_by_name"));
    }

    private static IngredientUnit unit(String value) {
        try {
            return value == null ? null : IngredientUnit.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
