package com.beaconculinary.api.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T0-4a: SQL for site_settings and site_setting_changes (V113). Values are the settings' stored
 * text; times are UTC LocalDateTimes from the application clock. Runs in the caller's transaction.
 */
@Repository
public class SiteSettingsStore {
    private final JdbcTemplate jdbc;

    public SiteSettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A saved value (value is null only for a nullable setting saved as empty). */
    public record Saved(String key, String value, LocalDateTime updatedAt, String updatedByName) {
    }

    /** A value from the history; text is null for an empty nullable setting. */
    public record HistoricValue(String text) {
    }

    /** A history entry. Values are the effective values before and after, as stored text. */
    public record Change(long id, String key, String action, String oldValue, boolean oldIsDefault, String newValue,
                         boolean newIsDefault, LocalDateTime changedAt, long changedById, String changedByName) {
    }

    private static final RowMapper<Change> CHANGE = (rs, i) -> new Change(rs.getLong("id"), rs.getString("setting_key"),
            rs.getString("action"), rs.getString("old_value"), rs.getBoolean("old_is_default"), rs.getString("new_value"),
            rs.getBoolean("new_is_default"), rs.getObject("changed_at", LocalDateTime.class), rs.getLong("changed_by"),
            rs.getString("changed_by_name"));

    private static final String CHANGE_COLUMNS = """
            [id], [setting_key], [action], [old_value], [old_is_default], [new_value], [new_is_default], [changed_at],
            [changed_by], [changed_by_name]""";

    public List<Saved> findAll() {
        return jdbc.query("SELECT [setting_key], [value], [updated_at], [updated_by_name] FROM [site_settings]",
                (rs, i) -> new Saved(rs.getString("setting_key"), rs.getString("value"),
                        rs.getObject("updated_at", LocalDateTime.class), rs.getString("updated_by_name")));
    }

    /** Serialises settings writes until the caller's transaction ends, so cross-field rules see every committed write. */
    public void lockForWrite() {
        jdbc.execute("EXEC sp_getapplock @Resource = 'site_settings', @LockMode = 'Exclusive', @LockOwner = 'Transaction', @LockTimeout = 10000");
    }

    public void upsert(String key, String value, LocalDateTime at, long byId, String byName) {
        int updated = jdbc.update("""
                UPDATE [site_settings] SET [value] = ?, [updated_at] = ?, [updated_by] = ?, [updated_by_name] = ?
                WHERE [setting_key] = ?""", value, at, byId, byName, key);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO [site_settings] ([setting_key], [value], [updated_at], [updated_by], [updated_by_name])
                    VALUES (?, ?, ?, ?, ?)""", key, value, at, byId, byName);
        }
    }

    /** Whether a saved value was removed. */
    public boolean delete(String key) {
        return jdbc.update("DELETE FROM [site_settings] WHERE [setting_key] = ?", key) > 0;
    }

    public void insertChange(String key, String action, String oldValue, boolean oldIsDefault, String newValue,
                             boolean newIsDefault, LocalDateTime at, long byId, String byName) {
        jdbc.update("""
                INSERT INTO [site_setting_changes] ([setting_key], [action], [old_value], [old_is_default], [new_value],
                    [new_is_default], [changed_at], [changed_by], [changed_by_name])
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                key, action, oldValue, oldIsDefault, newValue, newIsDefault, at, byId, byName);
    }

    /** The newest change of each key. */
    public Map<String, Change> lastChanges() {
        var result = new HashMap<String, Change>();
        jdbc.query("SELECT " + CHANGE_COLUMNS + """
                 FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY [setting_key] ORDER BY [changed_at] DESC, [id] DESC) AS [n]
                       FROM [site_setting_changes]) c
                WHERE [n] = 1""", CHANGE).forEach(change -> result.put(change.key(), change));
        return result;
    }

    /** Newest first; key null for every key. */
    public List<Change> history(String key, int page, int size) {
        var offset = (long) page * size;
        if (key == null) {
            return jdbc.query("SELECT " + CHANGE_COLUMNS + " FROM [site_setting_changes]"
                    + " ORDER BY [changed_at] DESC, [id] DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", CHANGE, offset, size);
        }
        return jdbc.query("SELECT " + CHANGE_COLUMNS + " FROM [site_setting_changes] WHERE [setting_key] = ?"
                + " ORDER BY [changed_at] DESC, [id] DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", CHANGE, key, offset, size);
    }

    public long countHistory(String key) {
        var count = key == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM [site_setting_changes]", Long.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM [site_setting_changes] WHERE [setting_key] = ?", Long.class, key);
        return count == null ? 0 : count;
    }

    /**
     * The value a setting had at a time, from the history: the value after the last change at or
     * before it; else the value before the first change after it. Empty when the key has never
     * changed (the current value applies).
     */
    public Optional<HistoricValue> valueAt(String key, LocalDateTime at) {
        var before = jdbc.query("""
                SELECT TOP 1 [new_value] FROM [site_setting_changes] WHERE [setting_key] = ? AND [changed_at] <= ?
                ORDER BY [changed_at] DESC, [id] DESC""", (rs, i) -> new HistoricValue(rs.getString(1)), key, at);
        if (!before.isEmpty()) {
            return Optional.of(before.get(0));
        }
        var after = jdbc.query("""
                SELECT TOP 1 [old_value] FROM [site_setting_changes] WHERE [setting_key] = ? AND [changed_at] > ?
                ORDER BY [changed_at], [id]""", (rs, i) -> new HistoricValue(rs.getString(1)), key, at);
        return after.isEmpty() ? Optional.empty() : Optional.of(after.get(0));
    }
}
