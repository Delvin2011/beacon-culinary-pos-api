package com.beaconculinary.api.settings;

import com.beaconculinary.api.offline.OfflineProperties;
import com.beaconculinary.api.tills.TillProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** T0-4a: SiteSettings without a database, for unit tests: the shipped defaults plus values saved in memory. */
public final class SiteSettingsFixture {
    private SiteSettingsFixture() {
    }

    /** A store over a list, counting reads. */
    public static class FakeStore extends SiteSettingsStore {
        final List<Saved> rows = new ArrayList<>();
        int reads;

        public FakeStore() {
            super(null);
        }

        @Override
        public List<Saved> findAll() {
            reads++;
            return List.copyOf(rows);
        }

        /** Saves a value as its stored text (null for an empty nullable setting). */
        public void save(String key, String value) {
            rows.removeIf(row -> row.key().equals(key));
            rows.add(new Saved(key, value, LocalDateTime.of(2026, 11, 19, 8, 0), "Canteen Admin"));
        }
    }

    /**
     * Test database only: removes every saved value and the whole history, so no test sees another's
     * changes. The history matters beyond the admin screen: an offline sale's VAT is checked against
     * the rate in force when it was made, read from the history. The insert-only trigger is lifted
     * just for this delete, as ReportFixture does for Z readings. Call SiteSettings.invalidate() after.
     */
    public static void clearSavedAndHistory(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM site_settings");
        jdbc.execute("DISABLE TRIGGER trg_site_setting_changes_insert_only ON site_setting_changes; "
                + "DELETE FROM site_setting_changes; "
                + "ENABLE TRIGGER trg_site_setting_changes_insert_only ON site_setting_changes;");
    }

    /** The defaults application.yaml ships. */
    public static SiteSettingDefaults shippedDefaults() {
        return new SiteSettingDefaults(new TillProperties(), new OfflineProperties(), "0.15", "2026-10-12", "10", "50.00",
                "3.0", "25", "12:30", "06:00-15:00");
    }

    public static SiteSettings settings(FakeStore store, Clock clock) {
        return new SiteSettings(store, shippedDefaults(), clock);
    }

    /** The shipped defaults with some values saved, e.g. settings("board.done-display-minutes", "15"). */
    public static SiteSettings settings(String... keyValuePairs) {
        var store = new FakeStore();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            store.save(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return settings(store, Clock.systemUTC());
    }
}
