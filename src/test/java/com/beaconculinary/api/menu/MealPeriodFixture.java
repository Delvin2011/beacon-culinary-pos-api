package com.beaconculinary.api.menu;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T0-5a test data: periods named "T05 …" (and inactive copies of seeded names) inserted directly,
 * and their removal with everything that references them. Also puts the seeded periods back
 * (names, times, active, sort order) and removes meal period history, lifting the insert-only
 * trigger for that delete only. Test database only.
 */
public final class MealPeriodFixture {
    public static final String PREFIX = "T05 ";

    private MealPeriodFixture() {
    }

    /** Inserts a timed SET_MEALS period and returns its id. */
    public static long insert(JdbcTemplate jdbc, String name, String start, String end, boolean active, int sortOrder) {
        return jdbc.queryForObject("""
                INSERT INTO [meal_periods] ([name], [start_time], [end_time], [all_day], [accepts], [active], [sort_order])
                OUTPUT inserted.[id]
                VALUES (?, ?, ?, 0, 'SET_MEALS', ?, ?)""", Long.class, name, start, end, active, sortOrder);
    }

    /** Removes test periods (by prefix, and inactive rows that copy a seeded name), their plan items and all
     * meal period history; restores the seeded periods. */
    public static void clear(JdbcTemplate jdbc) {
        var testPeriods = "SELECT [id] FROM [meal_periods] WHERE [id] > 3 OR [name] LIKE '" + PREFIX + "%'";
        jdbc.update("DELETE FROM [daily_component_stock] WHERE [meal_period_id] IN (" + testPeriods + ")");
        jdbc.update("DELETE FROM [daily_meal_options] WHERE [meal_period_id] IN (" + testPeriods + ")");
        jdbc.execute("DISABLE TRIGGER [trg_meal_period_changes_insert_only] ON [meal_period_changes]; "
                + "DELETE FROM [meal_period_changes]; "
                + "ENABLE TRIGGER [trg_meal_period_changes_insert_only] ON [meal_period_changes];");
        jdbc.update("DELETE FROM [meal_periods] WHERE [id] IN (" + testPeriods + ")");
        jdbc.update("""
                UPDATE [meal_periods] SET
                    [name] = CASE [id] WHEN 1 THEN 'Breakfast' WHEN 2 THEN 'Lunch' ELSE 'All Day' END,
                    [start_time] = CASE [id] WHEN 1 THEN '07:00' WHEN 2 THEN '12:00' END,
                    [end_time] = CASE [id] WHEN 1 THEN '10:00' WHEN 2 THEN '14:30' END,
                    [active] = 1,
                    [sort_order] = [id] * 10
                WHERE [id] IN (1, 2, 3)""");
    }
}
