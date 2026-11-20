package com.beaconculinary.api.menu;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** When a period is open: ends inclusive, All Day always, an inactive period never (T0-5a). */
class MealPeriodTest {
    private static MealPeriod timed(String start, String end) {
        var period = new MealPeriod();
        period.setStartTime(LocalTime.parse(start));
        period.setEndTime(LocalTime.parse(end));
        return period;
    }

    @Test
    void aTimedPeriodIsOpenFromStartToEndInclusive() {
        var lunch = timed("12:00", "14:30");
        assertThat(lunch.isActiveAt(LocalTime.of(11, 59, 59))).isFalse();
        assertThat(lunch.isActiveAt(LocalTime.of(12, 0))).isTrue();
        assertThat(lunch.isActiveAt(LocalTime.of(14, 30))).isTrue();
        assertThat(lunch.isActiveAt(LocalTime.of(14, 30, 1))).isFalse();
    }

    @Test
    void allDayIsAlwaysOpen() {
        var allDay = new MealPeriod();
        allDay.setAllDay(true);
        assertThat(allDay.isActiveAt(LocalTime.MIDNIGHT)).isTrue();
        assertThat(allDay.isActiveAt(LocalTime.of(23, 59))).isTrue();
    }

    @Test
    void anInactivePeriodIsNeverOpen() {
        var lunch = timed("12:00", "14:30");
        lunch.setActive(false);
        assertThat(lunch.isActiveAt(LocalTime.of(13, 0))).isFalse();

        var allDay = new MealPeriod();
        allDay.setAllDay(true);
        allDay.setActive(false);
        assertThat(allDay.isActiveAt(LocalTime.NOON)).isFalse();
    }

    @Test
    void aNewPeriodIsActive() {
        assertThat(new MealPeriod().isActive()).isTrue();
    }
}
