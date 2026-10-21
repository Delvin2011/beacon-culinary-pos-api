package com.beaconculinary.api.reports;

import com.beaconculinary.api.common.ClockConfig;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reports R1 §1.4: turns raw query parameters into typed values per a report's spec. Applies
 * defaults (DATE defaults "today"/"today-N" are Johannesburg days), ignores parameters the spec
 * doesn't declare, and rejects bad values with field-level messages. When a spec has from/to:
 * from must not be after to, and the range is at most 366 days inclusive. R2: a required
 * parameter that's missing is refused ("Select an account"), an ID must be one of its spec's
 * options, and a LIMIT is "ALL" or a positive whole number (kept as text, e.g. "ALL" or "10").
 */
@Component
@AllArgsConstructor
public class ReportParamParser {
    static final int MAX_RANGE_DAYS = 366;

    private final Clock clock;

    public ReportParams parse(List<ReportParamSpec> specs, Map<String, String> raw) {
        return parse(specs, raw, false);
    }

    /**
     * R3: page and size are generic query parameters for paged reports (page 0-based, size 1 to
     * 200, default 50). They're always validated but only paged reports use them. An export
     * (allRows) ignores them and gets every row.
     */
    public ReportParams parse(List<ReportParamSpec> specs, Map<String, String> raw, boolean allRows) {
        var values = new LinkedHashMap<String, Object>();
        var errors = new LinkedHashMap<String, String>();
        var paging = allRows ? ReportParams.Paging.ALL : paging(raw, errors);

        for (var spec : specs) {
            var input = raw.get(spec.name());
            var text = input == null || input.isBlank() ? spec.defaultValue() : input.trim();
            if (text == null) {
                if (spec.requiresValue()) {
                    errors.put(spec.name(), missing(spec));
                }
                values.put(spec.name(), null);
                continue;
            }
            try {
                values.put(spec.name(), convert(spec, text));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                errors.put(spec.name(), message(spec));
            }
        }

        // A parameter that only applies to other values of another one is ignored (null) there.
        for (var spec : specs) {
            var condition = spec.appliesWhen();
            if (condition != null && !condition.values().contains(String.valueOf(values.get(condition.param())))) {
                values.put(spec.name(), null);
                errors.remove(spec.name());
            }
        }

        if (errors.isEmpty() && values.get("from") instanceof LocalDate from && values.get("to") instanceof LocalDate to) {
            if (from.isAfter(to)) {
                errors.put("from", "From must be on or before To.");
            } else if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
                errors.put("to", "Range can't exceed " + MAX_RANGE_DAYS + " days.");
            }
        }

        if (!errors.isEmpty()) {
            throw new ReportParamException(errors);
        }
        return new ReportParams(values, paging);
    }

    private static ReportParams.Paging paging(Map<String, String> raw, Map<String, String> errors) {
        var number = 0;
        var size = ReportParams.Paging.DEFAULT_SIZE;
        try {
            var page = raw.get("page");
            if (page != null && !page.isBlank()) {
                number = Integer.parseInt(page.trim());
                if (number < 0) {
                    throw new NumberFormatException();
                }
            }
        } catch (NumberFormatException e) {
            errors.put("page", "Page must be 0 or more.");
        }
        try {
            var text = raw.get("size");
            if (text != null && !text.isBlank()) {
                size = Integer.parseInt(text.trim());
                if (size < 1 || size > ReportParams.Paging.MAX_SIZE) {
                    throw new NumberFormatException();
                }
            }
        } catch (NumberFormatException e) {
            errors.put("size", "Size must be between 1 and " + ReportParams.Paging.MAX_SIZE + ".");
        }
        return new ReportParams.Paging(number, size, false);
    }

    private Object convert(ReportParamSpec spec, String text) {
        return switch (spec.type()) {
            case DATE -> date(text);
            case ENUM -> {
                var upper = text.toUpperCase(Locale.ROOT);
                if (spec.options() == null || !spec.options().contains(upper)) {
                    throw new IllegalArgumentException(text);
                }
                yield upper;
            }
            case BOOLEAN -> {
                if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException(text);
                }
                yield Boolean.parseBoolean(text);
            }
            case USER -> Long.valueOf(text);
            case INT -> Integer.valueOf(text);
            case ID -> {
                var id = Long.valueOf(text);
                if (spec.options() != null && spec.optionLabel(id) == null) {
                    throw new IllegalArgumentException(text);
                }
                yield id;
            }
            case LIMIT -> {
                if (text.equalsIgnoreCase("ALL")) {
                    yield "ALL";
                }
                var rows = Integer.parseInt(text);
                if (rows <= 0) {
                    throw new IllegalArgumentException(text);
                }
                yield Integer.toString(rows);
            }
        };
    }

    private LocalDate date(String text) {
        if (text.equals("today")) {
            return today();
        }
        if (text.startsWith("today-")) {
            return today().minusDays(Long.parseLong(text.substring("today-".length())));
        }
        return LocalDate.parse(text);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ClockConfig.BUSINESS_ZONE);
    }

    private static String message(ReportParamSpec spec) {
        return switch (spec.type()) {
            case DATE -> spec.label() + " must be a date in the format yyyy-MM-dd.";
            case ENUM -> spec.label() + " must be one of "
                    + spec.options().stream().map(Object::toString).collect(Collectors.joining(", ")) + ".";
            case BOOLEAN -> spec.label() + " must be true or false.";
            case USER -> spec.label() + " must be a user id.";
            case ID -> select(spec);
            case LIMIT -> spec.label() + " must be ALL or a positive whole number.";
            case INT -> spec.label() + " must be a whole number.";
        };
    }

    private static String missing(ReportParamSpec spec) {
        return spec.type() == ParamType.ID ? select(spec) : spec.label() + " is required.";
    }

    /** "Select an account", "Select a meal period". */
    private static String select(ReportParamSpec spec) {
        var noun = spec.label().toLowerCase(Locale.ROOT);
        return "Select " + ("aeiou".indexOf(noun.charAt(0)) >= 0 ? "an " : "a ") + noun;
    }
}
