package com.beaconculinary.api.menu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T0-5a: what V114 adds to meal_periods, and the insert-only meal_period_changes. */
@SpringBootTest
class MealPeriodSchemaIntegrationTests {
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        MealPeriodFixture.clear(jdbc);
    }

    @Test
    void theSeededPeriodsAreActiveInTodaysOrder() {
        var rows = jdbc.queryForList("SELECT [name], [active], [sort_order] FROM [meal_periods] WHERE [id] IN (1, 2, 3) ORDER BY [sort_order]");
        assertThat(rows).extracting(row -> row.get("name")).containsExactly("Breakfast", "Lunch", "All Day");
        assertThat(rows).extracting(row -> row.get("sort_order")).containsExactly(10, 20, 30);
        assertThat(rows).extracting(row -> row.get("active")).containsOnly(true);
    }

    @Test
    void aTimedPeriodMustStartBeforeItEnds() {
        assertThatThrownBy(() -> MealPeriodFixture.insert(jdbc, "T05 Backwards", "14:00", "13:00", true, 90))
                .hasMessageContaining("CK_meal_periods_times");
        assertThatThrownBy(() -> MealPeriodFixture.insert(jdbc, "T05 Empty", "14:00", "14:00", true, 90))
                .hasMessageContaining("CK_meal_periods_times");
        assertThatThrownBy(() -> jdbc.update("UPDATE [meal_periods] SET [end_time] = NULL WHERE [id] = 1"))
                .hasMessageContaining("CK_meal_periods_times");
    }

    @Test
    void allDayHasNoTimesAndThereIsOnlyOne() {
        assertThatThrownBy(() -> jdbc.update("UPDATE [meal_periods] SET [start_time] = '06:00', [end_time] = '18:00' WHERE [all_day] = 1"))
                .hasMessageContaining("CK_meal_periods_times");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO [meal_periods] ([name], [all_day], [accepts], [active], [sort_order])
                VALUES ('T05 Second All Day', 1, 'LIGHT_MEALS', 1, 90)"""))
                .hasMessageContaining("UX_meal_periods_one_all_day");
    }

    @Test
    void namesAreUniqueAmongActivePeriodsIgnoringCase() {
        assertThatThrownBy(() -> MealPeriodFixture.insert(jdbc, "lunch", "16:00", "17:00", true, 90))
                .hasMessageContaining("UX_meal_periods_active_name");
        var inactiveCopy = MealPeriodFixture.insert(jdbc, "Lunch", "16:00", "17:00", false, 90);
        assertThat(inactiveCopy).isPositive();
        assertThatThrownBy(() -> jdbc.update("UPDATE [meal_periods] SET [active] = 1 WHERE [id] = ?", inactiveCopy))
                .hasMessageContaining("UX_meal_periods_active_name");
    }

    @Test
    void historyIsInsertOnlyAndOnlyCreateHasNoBefore() {
        jdbc.update("""
                INSERT INTO [meal_period_changes] ([meal_period_id], [action], [new_name], [new_start_time], [new_end_time],
                    [new_sort_order], [new_active], [changed_at], [changed_by], [changed_by_name])
                VALUES (1, 'CREATE', 'Breakfast', '07:00', '10:00', 10, 1, '2026-11-20T08:00:00', 1, 'Canteen Admin')""");
        assertThatThrownBy(() -> jdbc.update("UPDATE [meal_period_changes] SET [new_name] = 'X' WHERE [meal_period_id] = 1"))
                .hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM [meal_period_changes] WHERE [meal_period_id] = 1"))
                .hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO [meal_period_changes] ([meal_period_id], [action], [new_name], [new_sort_order], [new_active],
                    [changed_at], [changed_by], [changed_by_name])
                VALUES (1, 'UPDATE', 'Breakfast', 10, 1, '2026-11-20T08:00:00', 1, 'Canteen Admin')"""))
                .hasMessageContaining("CK_meal_period_changes_before");
    }
}
