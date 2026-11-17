package com.beaconculinary.api.endofday;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * O3: day_close_overrides (V110), insert-only: who closed a day despite a blocker, when, why, and
 * what was overridden (detail, JSON). Runs in Close Day's transaction.
 */
@Repository
public class DayCloseOverrides {
    private final JdbcTemplate jdbc;

    public DayCloseOverrides(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(LocalDate tradingDay, String blockerCode, String reason, String detailJson, long overriddenBy,
                       String overriddenByName, LocalDateTime overriddenAt) {
        jdbc.update("""
                INSERT INTO [day_close_overrides] ([trading_day], [blocker_code], [reason], [detail], [overridden_by],
                    [overridden_by_name], [overridden_at])
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                java.sql.Date.valueOf(tradingDay), blockerCode, reason, detailJson, overriddenBy, overriddenByName,
                overriddenAt);
    }
}
