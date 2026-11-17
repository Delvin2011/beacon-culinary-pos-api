package com.beaconculinary.api.shifts;

import com.beaconculinary.api.users.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * O2: the insert-only audit of force closes, late counts and confirmed no-counts
 * (shift_cash_count_events, V107; a trigger refuses UPDATE and DELETE). The admin's name is kept
 * with the row, so it still reads correctly if the user is later deleted.
 */
@Repository
public class ShiftCashCountEvents {
    public enum Action {
        FORCE_CLOSED,
        LATE_COUNT,
        NO_COUNT_CONFIRMED
    }

    private final JdbcTemplate jdbc;

    public ShiftCashCountEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long shiftId, Action action, BigDecimal countedCash, BigDecimal expectedCash, BigDecimal variance,
                       ShiftVarianceReasonCode reasonCode, String note, User by, LocalDateTime atUtc) {
        jdbc.update("""
                INSERT INTO [shift_cash_count_events] ([shift_id], [action], [counted_cash], [expected_cash], [variance],
                    [variance_reason_code], [note], [done_by], [done_by_name], [done_at])
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, shiftId, action.name(), countedCash, expectedCash, variance,
                reasonCode == null ? null : reasonCode.name(), note, by.getId(), by.getName(), atUtc);
    }
}
