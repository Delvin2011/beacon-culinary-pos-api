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

/**
 * Reports R1 §1.4: turns raw query parameters into typed values per a report's spec. Applies
 * defaults (DATE defaults "today"/"today-N" are Johannesburg days), ignores parameters the spec
 * doesn't declare, and rejects bad values with field-level messages. When a spec has from/to:
 * from must not be after to, and the range is at most 366 days inclusive.
 */
@Component
@AllArgsConstructor
public class ReportParamParser {
    static final int MAX_RANGE_DAYS = 366;

    private final Clock clock;

    public ReportParams parse(List<ReportParamSpec> specs, Map<String, String> raw) {
        var values = new LinkedHashMap<String, Object>();
        var errors = new LinkedHashMap<String, String>();

        for (var spec : specs) {
            var input = raw.get(spec.name());
            var text = input == null || input.isBlank() ? spec.defaultValue() : input.trim();
            if (text == null) {
                values.put(spec.name(), null);
                continue;
            }
            try {
                values.put(spec.name(), convert(spec, text));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                errors.put(spec.name(), message(spec));
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
        return new ReportParams(values);
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
            case ENUM -> spec.label() + " must be one of " + String.join(", ", spec.options()) + ".";
            case BOOLEAN -> spec.label() + " must be true or false.";
            case USER -> spec.label() + " must be a user id.";
        };
    }
}
