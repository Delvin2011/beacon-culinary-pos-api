package com.beaconculinary.api.menu;

import com.beaconculinary.api.common.locale.SiteLocale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** T0-5a step 5: a period's values at a past time, and whether it was open then (MealPeriodHistory). */
@SpringBootTest
class MealPeriodHistoryIntegrationTests {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MealPeriodChangeStore store;
    @Autowired
    private MealPeriodHistory history;
    @Autowired
    private MealPeriodRepository repository;
    @Autowired
    private SiteLocale siteLocale;

    @BeforeEach
    void setUp() {
        MealPeriodFixture.clear(jdbc);
    }

    @AfterEach
    void tearDown() {
        MealPeriodFixture.clear(jdbc);
    }

    /** A site-local time on DAY as an instant. */
    private Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(siteLocale.zone()).toInstant();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private void change(long periodId, String action, String oldStart, Boolean oldActive, String newStart, boolean newActive,
                        Instant changedAt) {
        var create = action.equals("CREATE");
        jdbc.update("""
                INSERT INTO [meal_period_changes] ([meal_period_id], [action], [old_name], [old_start_time], [old_end_time],
                    [old_sort_order], [old_active], [new_name], [new_start_time], [new_end_time], [new_sort_order],
                    [new_active], [changed_at], [changed_by], [changed_by_name])
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Lunch', ?, '14:30', 20, ?, ?, 1, 'History test')""",
                periodId, action, create ? null : "Lunch", oldStart, create ? null : "14:30", create ? null : 20,
                oldActive, newStart, newActive, utc(changedAt));
    }

    private MealPeriod lunch() {
        return repository.findById(2L).orElseThrow();
    }

    @Test
    void withNoHistoryTheCurrentValuesApply() {
        assertThat(store.valuesAt(2, utc(at(12, 0)))).isEmpty();
        assertThat(history.wasOpenAt(lunch(), at(12, 15))).isTrue();
        assertThat(history.wasOpenAt(lunch(), at(11, 59))).isFalse();
    }

    @Test
    void theLastChangeAtOrBeforeTheTimeApplies_elseTheValuesBeforeTheFirstLaterChange() {
        change(2, "UPDATE", "12:00", true, "13:00", true, at(10, 0));   // 10:00: Lunch starts 13:00
        change(2, "UPDATE", "13:00", true, "12:00", true, at(16, 0));   // 16:00: back to 12:00

        assertThat(store.valuesAt(2, utc(at(9, 0)))).map(MealPeriodAdminDtos.Values::startTime).hasValueSatisfying(
                start -> assertThat(start).hasToString("12:00"));
        assertThat(store.valuesAt(2, utc(at(10, 0)))).map(MealPeriodAdminDtos.Values::startTime).hasValueSatisfying(
                start -> assertThat(start).hasToString("13:00"));
        assertThat(history.wasOpenAt(lunch(), at(12, 30))).isFalse();   // 13:00-14:30 then
        assertThat(history.wasOpenAt(lunch(), at(13, 30))).isTrue();
    }

    @Test
    void aPeriodDeactivatedThenWasClosedFromThenOn() {
        change(2, "DEACTIVATE", "12:00", true, "12:00", false, at(12, 20));
        change(2, "ACTIVATE", "12:00", false, "12:00", true, at(12, 40));

        assertThat(history.wasOpenAt(lunch(), at(12, 10))).isTrue();
        assertThat(history.wasOpenAt(lunch(), at(12, 20))).isFalse();
        assertThat(history.wasOpenAt(lunch(), at(12, 30))).isFalse();
        assertThat(history.wasOpenAt(lunch(), at(12, 40))).isTrue();
    }

    @Test
    void aPeriodCreatedLaterUsesItsCurrentValues() {
        var supper = MealPeriodFixture.insert(jdbc, "T05 Supper", "17:00", "19:00", true, 40);
        jdbc.update("""
                INSERT INTO [meal_period_changes] ([meal_period_id], [action], [new_name], [new_start_time], [new_end_time],
                    [new_sort_order], [new_active], [changed_at], [changed_by], [changed_by_name])
                VALUES (?, 'CREATE', 'T05 Supper', '17:00', '19:00', 40, 1, ?, 1, 'History test')""", supper, utc(at(20, 0)));

        assertThat(store.valuesAt(supper, utc(at(18, 0)))).isEmpty();
        assertThat(history.wasOpenAt(repository.findById(supper).orElseThrow(), at(18, 0))).isTrue();
    }

    @Test
    void changesAtTheSameInstantAreOrderedById() {
        change(2, "UPDATE", "12:00", true, "13:00", true, at(10, 0));
        change(2, "UPDATE", "13:00", true, "12:30", true, at(10, 0));

        assertThat(history.wasOpenAt(lunch(), at(12, 45))).isTrue();    // 12:30-14:30, the later row
        assertThat(history.wasOpenAt(lunch(), at(12, 15))).isFalse();
    }

    @Test
    void allDayIsOpenWhateverItsHistory() {
        var allDay = repository.findById(3L).orElseThrow();
        assertThat(history.wasOpenAt(allDay, at(3, 0))).isTrue();
    }
}
