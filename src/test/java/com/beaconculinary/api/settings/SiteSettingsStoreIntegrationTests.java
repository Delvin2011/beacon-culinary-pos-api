package com.beaconculinary.api.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T0-4a: the V113 tables through SiteSettingsStore: upsert and delete, the insert-only history,
 * the newest change per key, paging, and the value a setting had at a time. Test keys start with
 * "test." so they never clash with real settings; their rows and history are removed after each
 * test (lifting the insert-only trigger for that delete only).
 */
@SpringBootTest
class SiteSettingsStoreIntegrationTests {
    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 19, 8, 0);

    @Autowired
    private SiteSettingsStore store;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;

    private String key;

    @BeforeEach
    void setUp() {
        key = "test.store." + System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM [site_settings] WHERE [setting_key] LIKE 'test.%'");
        jdbc.execute("DISABLE TRIGGER trg_site_setting_changes_insert_only ON site_setting_changes; "
                + "DELETE FROM site_setting_changes WHERE setting_key LIKE 'test.%'; "
                + "ENABLE TRIGGER trg_site_setting_changes_insert_only ON site_setting_changes;");
    }

    @Test
    void upsertInsertsThenUpdatesAndDeleteRemoves() {
        store.upsert(key, "1", T, 1, "Canteen Admin");
        store.upsert(key, "2", T.plusMinutes(1), 2, "Other Admin");
        var row = store.findAll().stream().filter(saved -> saved.key().equals(key)).findFirst().orElseThrow();
        assertThat(row.value()).isEqualTo("2");
        assertThat(row.updatedByName()).isEqualTo("Other Admin");
        assertThat(store.delete(key)).isTrue();
        assertThat(store.delete(key)).isFalse();
    }

    @Test
    void historyIsInsertOnly() {
        store.insertChange(key, "SET", "1", true, "2", false, T, 1, "Canteen Admin");
        assertThatThrownBy(() -> jdbc.update("UPDATE [site_setting_changes] SET [new_value] = '3' WHERE [setting_key] = ?", key))
                .hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM [site_setting_changes] WHERE [setting_key] = ?", key))
                .hasMessageContaining("insert-only");
        assertThat(store.countHistory(key)).isEqualTo(1);
    }

    @Test
    void historyNewestFirstPagedAndTheLastChangePerKey() {
        store.insertChange(key, "SET", "1", true, "2", false, T, 1, "A");
        store.insertChange(key, "SET", "2", false, "3", false, T.plusMinutes(5), 1, "A");
        store.insertChange(key, "RESET", "3", false, "1", true, T.plusMinutes(10), 1, "B");

        var first = store.history(key, 0, 2);
        assertThat(first).extracting(SiteSettingsStore.Change::action).containsExactly("RESET", "SET");
        assertThat(store.history(key, 1, 2)).extracting(SiteSettingsStore.Change::newValue).containsExactly("2");
        assertThat(store.countHistory(key)).isEqualTo(3);
        assertThat(store.countHistory(null)).isGreaterThanOrEqualTo(3);
        assertThat(store.history(null, 0, 1)).hasSize(1);

        var last = store.lastChanges().get(key);
        assertThat(last.action()).isEqualTo("RESET");
        assertThat(last.changedByName()).isEqualTo("B");
        assertThat(last.newIsDefault()).isTrue();
    }

    @Test
    void valueAtATimeComesFromTheHistory() {
        assertThat(store.valueAt(key, T)).isEmpty();
        store.insertChange(key, "SET", "0.15", true, "0.16", false, T, 1, "A");
        store.insertChange(key, "SET", "0.16", false, "0.17", false, T.plusDays(1), 1, "A");

        assertThat(store.valueAt(key, T.minusHours(1))).map(SiteSettingsStore.HistoricValue::text).contains("0.15");
        assertThat(store.valueAt(key, T)).map(SiteSettingsStore.HistoricValue::text).contains("0.16");
        assertThat(store.valueAt(key, T.plusHours(3))).map(SiteSettingsStore.HistoricValue::text).contains("0.16");
        assertThat(store.valueAt(key, T.plusDays(2))).map(SiteSettingsStore.HistoricValue::text).contains("0.17");
    }

    @Test
    void theWriteLockIsTakenInsideATransaction() {
        transactions.executeWithoutResult(status -> {
            store.lockForWrite();
            store.upsert(key, "1", T, 1, "A");
        });
        assertThat(store.findAll()).extracting(SiteSettingsStore.Saved::key).contains(key);
    }

    @Test
    void offlineGrantsHaveTheAccountColumns() {
        var columns = jdbc.queryForList("""
                SELECT [name] FROM sys.columns WHERE [object_id] = OBJECT_ID('offline_grants')
                AND [name] IN ('accounts_enabled', 'account_cap')""", String.class);
        assertThat(columns).containsExactlyInAnyOrder("accounts_enabled", "account_cap");
    }
}
