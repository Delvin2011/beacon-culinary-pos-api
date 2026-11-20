package com.beaconculinary.api.menu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** T0-5a: field rules and the overlap rule (contract 4.1 and 4.2). */
class MealPeriodRulesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static java.util.Map<String, String> errors(String body, boolean timed) throws Exception {
        var node = json(body);
        return catchThrowableOfType(() -> MealPeriodRules.read(node, timed), MealPeriodValidationException.class).getErrors();
    }

    private static MealPeriod period(String name, String start, String end, boolean active) {
        var period = new MealPeriod();
        period.setName(name);
        period.setStartTime(LocalTime.parse(start));
        period.setEndTime(LocalTime.parse(end));
        period.setActive(active);
        return period;
    }

    @Test
    void readsAValidBodyTrimmingTheNameAndAcceptingSeconds() throws Exception {
        var fields = MealPeriodRules.read(json("{\"name\":\"  Supper \",\"startTime\":\"17:00\",\"endTime\":\"19:00:00\",\"sortOrder\":40}"), true);
        assertThat(fields).isEqualTo(new MealPeriodRules.Fields("Supper", LocalTime.of(17, 0), LocalTime.of(19, 0), 40));
        assertThat(MealPeriodRules.read(json("{\"name\":\"Supper\",\"startTime\":\"17:00\",\"endTime\":\"19:00\"}"), true).sortOrder()).isNull();
    }

    @Test
    void listsEveryBadFieldAtOnce() throws Exception {
        assertThat(errors("{\"sortOrder\":-1}", true)).containsOnly(
                org.assertj.core.api.Assertions.entry("name", "Required."),
                org.assertj.core.api.Assertions.entry("startTime", "Required."),
                org.assertj.core.api.Assertions.entry("endTime", "Required."),
                org.assertj.core.api.Assertions.entry("sortOrder", "Must be between 0 and 9999."));
    }

    @Test
    void fieldMessages() throws Exception {
        assertThat(errors("{\"name\":\"" + "x".repeat(51) + "\",\"startTime\":\"7:00\",\"endTime\":\"25:00\",\"sortOrder\":1.5}", true))
                .containsEntry("name", "At most 50 characters.")
                .containsEntry("startTime", "Use HH:mm.")
                .containsEntry("endTime", "Use HH:mm.")
                .containsEntry("sortOrder", "Must be between 0 and 9999.");
        assertThat(errors("{\"name\":\" \",\"startTime\":\"07:00:30\",\"endTime\":7,\"sortOrder\":10000}", true))
                .containsEntry("name", "Required.")
                .containsEntry("startTime", "Seconds must be 00.")
                .containsEntry("endTime", "Use HH:mm.")
                .containsEntry("sortOrder", "Must be between 0 and 9999.");
    }

    @Test
    void noOvernightPeriods() throws Exception {
        var overnight = "Overnight periods are not supported; the end must be after the start.";
        assertThat(errors("{\"name\":\"Night\",\"startTime\":\"22:00\",\"endTime\":\"02:00\"}", true)).containsOnly(
                org.assertj.core.api.Assertions.entry("endTime", overnight));
        assertThat(errors("{\"name\":\"Instant\",\"startTime\":\"12:00\",\"endTime\":\"12:00\"}", true)).containsOnly(
                org.assertj.core.api.Assertions.entry("endTime", overnight));
    }

    @Test
    void allDayReadsNoTimes() throws Exception {
        var fields = MealPeriodRules.read(json("{\"name\":\"Anytime\"}"), false);
        assertThat(fields.startTime()).isNull();
        assertThat(MealPeriodRules.hasTimes(json("{\"name\":\"Anytime\",\"startTime\":null}"))).isFalse();
        assertThat(MealPeriodRules.hasTimes(json("{\"name\":\"Anytime\",\"endTime\":\"18:00\"}"))).isTrue();
    }

    @Test
    void touchingEndsDontOverlapButAnySharedTimeDoes() {
        var lunch = period("Lunch", "12:00", "14:30", true);
        var others = List.of(lunch);
        assertThat(MealPeriodRules.overlap(LocalTime.of(10, 0), LocalTime.of(12, 0), others)).isEmpty();
        assertThat(MealPeriodRules.overlap(LocalTime.of(14, 30), LocalTime.of(16, 0), others)).isEmpty();
        assertThat(MealPeriodRules.overlap(LocalTime.of(11, 0), LocalTime.of(12, 1), others)).contains(lunch);
        assertThat(MealPeriodRules.overlap(LocalTime.of(13, 0), LocalTime.of(13, 30), others)).contains(lunch);
        assertThat(MealPeriodRules.overlap(LocalTime.of(11, 0), LocalTime.of(15, 0), others)).contains(lunch);
    }

    @Test
    void inactiveAndAllDayPeriodsNeverOverlap() {
        var allDay = new MealPeriod();
        allDay.setAllDay(true);
        var others = List.of(period("Supper", "17:00", "19:00", false), allDay);
        assertThat(MealPeriodRules.overlap(LocalTime.of(17, 0), LocalTime.of(19, 0), others)).isEmpty();
    }
}
