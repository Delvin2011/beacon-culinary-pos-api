package com.beaconculinary.api.menu;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * T0-5a: the field rules of a meal period request and the overlap rule (contract section 4).
 * Pure: no database. The rules that need other rows (unique active name, overlap with saved
 * periods, items in use) are applied by MealPeriodAdminService under its lock.
 */
public final class MealPeriodRules {
    public static final int MAX_NAME = 50;
    public static final int MAX_SORT_ORDER = 9999;
    private static final Pattern TIME = Pattern.compile("\\d{2}:\\d{2}(:\\d{2})?");

    private MealPeriodRules() {
    }

    /** A request's values, read and checked. Times are null for All Day; sortOrder null when not sent. */
    public record Fields(String name, LocalTime startTime, LocalTime endTime, Integer sortOrder) {
    }

    /**
     * Reads a create or update body. {@code timed} is false for the All Day period, which takes no
     * times (the caller refuses times for it before this). Every bad field is listed at once.
     */
    public static Fields read(JsonNode body, boolean timed) {
        var errors = new LinkedHashMap<String, String>();
        var name = name(body.get("name"), errors);
        LocalTime start = null;
        LocalTime end = null;
        if (timed) {
            start = time(body.get("startTime"), "startTime", errors);
            end = time(body.get("endTime"), "endTime", errors);
            if (start != null && end != null && !start.isBefore(end)) {
                errors.put("endTime", "Overnight periods are not supported; the end must be after the start.");
            }
        }
        var sortOrder = sortOrder(body.get("sortOrder"), errors);
        if (!errors.isEmpty()) {
            throw new MealPeriodValidationException(errors);
        }
        return new Fields(name, start, end, sortOrder);
    }

    /** Whether a body carries a time (non-null startTime or endTime): refused for All Day. */
    public static boolean hasTimes(JsonNode body) {
        return present(body.get("startTime")) || present(body.get("endTime"));
    }

    /** The first active timed period that [start, end] overlaps; touching ends don't overlap. */
    public static Optional<MealPeriod> overlap(LocalTime start, LocalTime end, List<MealPeriod> others) {
        return others.stream()
                .filter(other -> other.isActive() && !other.isAllDay())
                .filter(other -> start.isBefore(other.getEndTime()) && other.getStartTime().isBefore(end))
                .findFirst();
    }

    private static String name(JsonNode node, Map<String, String> errors) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            errors.put("name", "Required.");
            return null;
        }
        var name = node.asText().trim();
        if (name.length() > MAX_NAME) {
            errors.put("name", "At most " + MAX_NAME + " characters.");
            return null;
        }
        return name;
    }

    private static LocalTime time(JsonNode node, String field, Map<String, String> errors) {
        if (!present(node)) {
            errors.put(field, "Required.");
            return null;
        }
        var text = node.isTextual() ? node.asText().trim() : "";
        if (!TIME.matcher(text).matches()) {
            errors.put(field, "Use HH:mm.");
            return null;
        }
        LocalTime time;
        try {
            time = LocalTime.parse(text);
        } catch (DateTimeParseException e) {
            errors.put(field, "Use HH:mm.");
            return null;
        }
        if (time.getSecond() != 0) {
            errors.put(field, "Seconds must be 00.");
            return null;
        }
        return time;
    }

    private static Integer sortOrder(JsonNode node, Map<String, String> errors) {
        if (!present(node)) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 0 || node.asInt() > MAX_SORT_ORDER) {
            errors.put("sortOrder", "Must be between 0 and " + MAX_SORT_ORDER + ".");
            return null;
        }
        return node.asInt();
    }

    private static boolean present(JsonNode node) {
        return node != null && !node.isNull();
    }
}
