package com.beaconculinary.api.tenancy.jobs;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1-6: when a daily job is due. Ticks every minute over several days, as the job's own tick does, with the state
 * {@link CustomerJobs} keeps (the last local date run): no real waiting, no clock.
 */
class DailyScheduleTest {
    private static final ZoneId JOHANNESBURG = ZoneId.of("Africa/Johannesburg");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime HALF_PAST_THREE = LocalTime.of(3, 30);
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 11, 20);

    @Test
    void twoCustomersInDifferentZones_eachRunsAtHalfPastThreeLocal_oncePerLocalDay() {
        var alpha = runs(FIRST_DAY, 3, HALF_PAST_THREE, JOHANNESBURG, "alpha");
        var bravo = runs(FIRST_DAY, 3, HALF_PAST_THREE, NEW_YORK, "bravo");

        // alpha: 03:30 + 17 min in Johannesburg (UTC+2) = 01:47 UTC, on 20, 21 and 22 November.
        assertThat(alpha).containsExactly(Instant.parse("2026-11-20T01:47:00Z"), Instant.parse("2026-11-21T01:47:00Z"),
                Instant.parse("2026-11-22T01:47:00Z"));
        // bravo: 03:30 + 13 min in New York (UTC-5 in November) = 08:43 UTC.
        assertThat(bravo).containsExactly(Instant.parse("2026-11-20T08:43:00Z"), Instant.parse("2026-11-21T08:43:00Z"),
                Instant.parse("2026-11-22T08:43:00Z"));
        for (var run : bravo) {
            assertThat(run.atZone(NEW_YORK).toLocalTime()).isEqualTo(LocalTime.of(3, 43));
        }
    }

    @Test
    void theSpread_isZeroToTwentyMinutes_differsBetweenCustomers_andIsAlwaysTheSameForOne() {
        assertThat(DailySchedule.offsetMinutes("alpha")).isEqualTo(17);
        assertThat(DailySchedule.offsetMinutes("bravo")).isEqualTo(13);
        assertThat(DailySchedule.offsetMinutes("charlie")).isEqualTo(20);
        assertThat(DailySchedule.offsetMinutes("alpha")).as("the same every time").isEqualTo(DailySchedule.offsetMinutes("alpha"));
        for (var slug : List.of("beaconculinary", "testcustomer", "a-b", "x".repeat(40))) {
            assertThat(DailySchedule.offsetMinutes(slug)).isBetween(0, DailySchedule.SPREAD_MINUTES);
        }
        // Two customers in one zone start at different minutes inside 03:30-03:50.
        var alpha = runs(FIRST_DAY, 1, HALF_PAST_THREE, JOHANNESBURG, "alpha").get(0);
        var bravo = runs(FIRST_DAY, 1, HALF_PAST_THREE, JOHANNESBURG, "bravo").get(0);
        assertThat(alpha.atZone(JOHANNESBURG).toLocalTime()).isEqualTo(LocalTime.of(3, 47));
        assertThat(bravo.atZone(JOHANNESBURG).toLocalTime()).isEqualTo(LocalTime.of(3, 43));
    }

    @Test
    void clocksGoForward_aTimeInTheGapStillRunsThatDay_once() {
        // New York, 8 March 2026: 02:00 EST jumps to 03:00 EDT, so 02:30 doesn't exist; it runs at 03:30 EDT instead.
        var time = LocalTime.of(2, 30);
        var runs = runs(LocalDate.of(2026, 3, 7), 3, time, NEW_YORK, "charlie");
        assertThat(runs).extracting(run -> run.atZone(NEW_YORK).toLocalDate())
                .containsExactly(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 9));
        assertThat(runs.get(0).atZone(NEW_YORK).toLocalTime()).isEqualTo(LocalTime.of(2, 50));
        assertThat(runs.get(1)).isEqualTo(Instant.parse("2026-03-08T07:50:00Z")); // 03:50 EDT
        assertThat(runs.get(2).atZone(NEW_YORK).toLocalTime()).isEqualTo(LocalTime.of(2, 50));
    }

    @Test
    void clocksGoBack_aTimeThatOccursTwiceRunsOnce() {
        // New York, 1 November 2026: 02:00 EDT goes back to 01:00 EST, so 01:00-02:00 happens twice.
        var time = LocalTime.of(1, 30);
        var runs = runs(LocalDate.of(2026, 10, 31), 3, time, NEW_YORK, "testcustomer");
        assertThat(runs).extracting(run -> run.atZone(NEW_YORK).toLocalDate())
                .containsExactly(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));
        // The first 01:33 (EDT, UTC-4), not the second (EST, 06:33 UTC).
        assertThat(runs.get(1)).isEqualTo(Instant.parse("2026-11-01T05:33:00Z"));
    }

    @Test
    void aRestartAfterTheTime_runsOnceLate_thatDay() {
        // Nothing recorded (a restart at 10:00): the day's run happens at once, and only once.
        var restart = ZonedDateTime.of(2026, 11, 20, 10, 0, 0, 0, JOHANNESBURG).toInstant();
        assertThat(DailySchedule.dueDate(restart, HALF_PAST_THREE, JOHANNESBURG, 17, null)).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(DailySchedule.dueDate(restart, HALF_PAST_THREE, JOHANNESBURG, 17, LocalDate.of(2026, 11, 20))).isNull();
    }

    @Test
    void aTimeTooLateForTheSpread_isRefused() {
        DailySchedule.check(LocalTime.of(23, 39));
        assertThatThrownBy(() -> DailySchedule.check(LocalTime.of(23, 40)))
                .hasMessageContaining("23:39 or earlier");
        assertThatThrownBy(() -> DailySchedule.check(null)).isInstanceOf(IllegalArgumentException.class);
    }

    /** The instants the job runs at, ticking every minute from local midnight of {@code firstDay}, for {@code days}. */
    private static List<Instant> runs(LocalDate firstDay, int days, LocalTime time, ZoneId zone, String slug) {
        var offset = DailySchedule.offsetMinutes(slug);
        var runs = new ArrayList<Instant>();
        LocalDate lastRun = null;
        var end = firstDay.plusDays(days).atStartOfDay(zone).toInstant();
        for (var now = firstDay.atStartOfDay(zone).toInstant(); now.isBefore(end); now = now.plusSeconds(60)) {
            var date = DailySchedule.dueDate(now, time, zone, offset, lastRun);
            if (date != null) {
                lastRun = date;
                runs.add(now);
            }
        }
        return runs;
    }
}
