package com.beaconculinary.api.shifts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O2 step 2: the database rules from V107, tested directly against the schema (the application
 * code arrives in later steps).
 *
 * <ul>
 *   <li>One open shift per till and one per cashier (UX_shifts_open_device, UX_shifts_open_cashier),
 *       replacing V40's single open shift.</li>
 *   <li>The NULL till: SQL Server treats NULLs as equal in a unique index, so at most one open shift
 *       can have no till. Unregistered browsers keep sharing one shift, as before O2.</li>
 *   <li>Till codes are unique among active tills only.</li>
 *   <li>user_sessions: ended_at and end_reason go together.</li>
 *   <li>shift_cash_count_events is insert-only.</li>
 * </ul>
 */
@SpringBootTest
class O2TillShiftIndexIntegrationTests {
    private static final String DEVICE_PREFIX = "O2 Index ";

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> shiftIds = new ArrayList<>();
    private final List<String> sessionIds = new ArrayList<>();
    private List<Long> users;

    @BeforeEach
    void setUp() {
        // Three users without an open shift, so the per-cashier index doesn't trip on leftovers.
        users = jdbc.queryForList("""
                SELECT TOP 3 u.id FROM users u
                WHERE NOT EXISTS (SELECT 1 FROM shifts s WHERE s.cashier_id = u.id AND s.status = 'OPEN')
                ORDER BY u.id
                """, Long.class);
        assertThat(users).hasSize(3);
    }

    @AfterEach
    void tearDown() {
        if (!shiftIds.isEmpty()) {
            var ids = shiftIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElseThrow();
            jdbc.execute("DISABLE TRIGGER trg_shift_cash_count_events_insert_only ON shift_cash_count_events; "
                    + "DELETE FROM shift_cash_count_events WHERE shift_id IN (" + ids + "); "
                    + "ENABLE TRIGGER trg_shift_cash_count_events_insert_only ON shift_cash_count_events;");
            jdbc.update("DELETE FROM shifts WHERE id IN (" + ids + ")");
        }
        for (var id : sessionIds) {
            jdbc.update("DELETE FROM user_sessions WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM devices WHERE name LIKE ?", DEVICE_PREFIX + "%");
    }

    // ---- one open shift per till, one per cashier ----

    @Test
    void v40SingleOpenIndexIsGone_andBothNewIndexesExist() {
        var names = jdbc.queryForList(
                "SELECT name FROM sys.indexes WHERE object_id = OBJECT_ID('shifts') AND is_unique = 1 AND has_filter = 1",
                String.class);
        assertThat(names).contains("UX_shifts_open_device", "UX_shifts_open_cashier").doesNotContain("idx_shifts_single_open");
    }

    @Test
    void threeTills_threeCashiers_canEachHaveAnOpenShift() {
        var t1 = device("T1", "ACTIVE");
        var t2 = device("T2", "ACTIVE");
        var t3 = device("T3", "ACTIVE");

        openShift(users.get(0), t1);
        openShift(users.get(1), t2);
        openShift(users.get(2), t3);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shifts WHERE status = 'OPEN' AND device_id IN (?, ?, ?)",
                Integer.class, t1, t2, t3)).isEqualTo(3);
    }

    @Test
    void secondOpenShiftOnTheSameTill_isRefused() {
        var t1 = device("T1", "ACTIVE");
        openShift(users.get(0), t1);

        assertThatThrownBy(() -> openShift(users.get(1), t1)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("UX_shifts_open_device");
    }

    @Test
    void sameCashierOnTwoTills_isRefused() {
        var t1 = device("T1", "ACTIVE");
        var t2 = device("T2", "ACTIVE");
        openShift(users.get(0), t1);

        assertThatThrownBy(() -> openShift(users.get(0), t2)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("UX_shifts_open_cashier");
    }

    @Test
    void sameCashierOnARegisteredTillAndTheNullTill_isRefused() {
        var t1 = device("T1", "ACTIVE");
        openShift(users.get(0), t1);

        assertThatThrownBy(() -> openShift(users.get(0), null)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("UX_shifts_open_cashier");
    }

    @Test
    void closedShiftsDontCount_onTheSameTillOrForTheSameCashier() {
        var t1 = device("T1", "ACTIVE");
        closedShift(users.get(0), t1);
        closedShift(users.get(0), t1);
        closedShift(users.get(1), null);
        closedShift(users.get(1), null);

        assertThatCode(() -> openShift(users.get(0), t1)).doesNotThrowAnyException();
    }

    // ---- the NULL till ----

    @Test
    void nullTill_allowsOnlyOneOpenShift_soUnregisteredBrowsersShareIt() {
        // SQL Server: NULLs are equal in a unique index, so a second OPEN row without a till clashes.
        var existing = jdbc.queryForObject("SELECT COUNT(*) FROM shifts WHERE status = 'OPEN' AND device_id IS NULL",
                Integer.class);
        if (existing == 0) {
            openShift(users.get(0), null);
        }

        assertThatThrownBy(() -> openShift(users.get(1), null)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("UX_shifts_open_device");
    }

    @Test
    void nullTillShift_andRegisteredTillShifts_coexist() {
        var t1 = device("T1", "ACTIVE");
        var existing = jdbc.queryForObject("SELECT COUNT(*) FROM shifts WHERE status = 'OPEN' AND device_id IS NULL",
                Integer.class);
        if (existing == 0) {
            openShift(users.get(0), null);
        }

        assertThatCode(() -> openShift(users.get(1), t1)).doesNotThrowAnyException();
    }

    @Test
    void newShiftsDefaultToCounted_andNotForceClosed() {
        var id = openShift(users.get(0), device("T1", "ACTIVE"));

        var row = jdbc.queryForMap("SELECT cash_count, force_closed FROM shifts WHERE id = ?", id);
        assertThat(row.get("cash_count")).isEqualTo("COUNTED");
        assertThat(row.get("force_closed")).isEqualTo(false);
    }

    @Test
    void cashCount_rejectsUnknownValues() {
        var id = openShift(users.get(0), device("T1", "ACTIVE"));

        assertThatThrownBy(() -> jdbc.update("UPDATE shifts SET cash_count = 'MAYBE' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- till codes ----

    @Test
    void tillCode_isUniqueAmongActiveTills() {
        device("T7", "ACTIVE");

        assertThatThrownBy(() -> device("T7", "ACTIVE")).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("UX_devices_active_till_code");
    }

    @Test
    void tillCode_ofARevokedTill_canBeReused() {
        device("T7", "REVOKED");
        device("T7", "REVOKED");

        assertThatCode(() -> device("T7", "ACTIVE")).doesNotThrowAnyException();
    }

    // ---- sessions ----

    @Test
    void session_endedAtAndEndReason_goTogether() {
        assertThatCode(() -> session(null, null)).doesNotThrowAnyException();
        assertThatCode(() -> session(LocalDateTime.now(), "LOCK")).doesNotThrowAnyException();
        assertThatThrownBy(() -> session(LocalDateTime.now(), null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> session(null, "LOCK")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> session(LocalDateTime.now(), "BORED")).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- cash-count audit ----

    @Test
    void cashCountEvents_areInsertOnly() {
        var shiftId = closedShift(users.get(0), null);
        jdbc.update("""
                INSERT INTO shift_cash_count_events (shift_id, action, expected_cash, note, done_by, done_by_name, done_at)
                VALUES (?, 'FORCE_CLOSED', 500.00, 'Cashier went home', ?, 'Admin', SYSUTCDATETIME())
                """, shiftId, users.get(1));

        assertThatThrownBy(() -> jdbc.update("UPDATE shift_cash_count_events SET note = 'x' WHERE shift_id = ?", shiftId))
                .hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM shift_cash_count_events WHERE shift_id = ?", shiftId))
                .hasMessageContaining("insert-only");
        assertThat(jdbc.queryForObject("SELECT note FROM shift_cash_count_events WHERE shift_id = ?", String.class, shiftId))
                .isEqualTo("Cashier went home");
    }

    // ---- helpers ----

    private String device(String tillCode, String status) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO devices (id, name, device_type, status, registered_by, registered_at, till_code)
                VALUES (?, ?, 'POS', ?, 0, SYSUTCDATETIME(), ?)
                """, id, DEVICE_PREFIX + tillCode + " " + id, status, tillCode);
        return id;
    }

    private long openShift(Long cashierId, String deviceId) {
        return insertShift(cashierId, deviceId, "OPEN");
    }

    private long closedShift(Long cashierId, String deviceId) {
        return insertShift(cashierId, deviceId, "CLOSED");
    }

    private long insertShift(Long cashierId, String deviceId, String status) {
        var id = jdbc.queryForObject("""
                INSERT INTO shifts (cashier_id, opening_float, status, device_id) OUTPUT INSERTED.id
                VALUES (?, 100.00, ?, ?)
                """, Long.class, cashierId, status, deviceId);
        shiftIds.add(id);
        return id;
    }

    private void session(LocalDateTime endedAt, String endReason) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO user_sessions (id, user_id, kind, method, started_at, last_seen_at, ended_at, end_reason)
                VALUES (?, ?, 'POS', 'pin', SYSUTCDATETIME(), SYSUTCDATETIME(), ?, ?)
                """, id, users.get(0), endedAt, endReason);
        sessionIds.add(id);
    }
}
