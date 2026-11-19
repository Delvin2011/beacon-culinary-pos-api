package com.beaconculinary.api.settings;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * T0-4a: the value types a site setting can have, with their three forms: the Java value
 * (BigDecimal, Integer, Boolean, LocalDate, LocalTime, TimeRange), the canonical text stored in
 * site_settings / site_setting_changes, and the JSON value of the admin API.
 */
public enum SettingType {
    DECIMAL, INTEGER, BOOLEAN, DATE, TIME, TIME_RANGE;

    private static final Pattern STRICT_TIME = Pattern.compile("\\d{2}:\\d{2}");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** Thrown when a value has the wrong form; the message is the one the API returns. */
    public static class BadValueException extends RuntimeException {
        public BadValueException(String message) {
            super(message);
        }
    }

    /** The value from its stored text. Throws BadValueException. */
    public Object fromText(String text) {
        try {
            return switch (this) {
                case DECIMAL -> new BigDecimal(text.trim());
                case INTEGER -> Integer.valueOf(text.trim());
                case BOOLEAN -> parseBoolean(text.trim());
                case DATE -> LocalDate.parse(text.trim());
                case TIME -> parseTime(text.trim(), false);
                case TIME_RANGE -> TimeRange.parse(text.trim(), false);
            };
        } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException e) {
            throw new BadValueException(formMessage());
        }
    }

    /** The stored text of a value (already checked). */
    public String toText(Object value) {
        return switch (this) {
            case DECIMAL -> ((BigDecimal) value).toPlainString();
            case TIME -> HH_MM.format((LocalTime) value);
            default -> value.toString();
        };
    }

    /** The value from the request's JSON (not null). Strict: numbers must be JSON numbers, times HH:mm. */
    public Object fromJson(JsonNode node) {
        switch (this) {
            case DECIMAL -> {
                if (!node.isNumber()) {
                    throw new BadValueException(formMessage());
                }
                return node.decimalValue();
            }
            case INTEGER -> {
                if (!node.isIntegralNumber() || !node.canConvertToInt()) {
                    throw new BadValueException(formMessage());
                }
                return node.intValue();
            }
            case BOOLEAN -> {
                if (!node.isBoolean()) {
                    throw new BadValueException(formMessage());
                }
                return node.booleanValue();
            }
            default -> {
                if (!node.isTextual()) {
                    throw new BadValueException(formMessage());
                }
                try {
                    return switch (this) {
                        case DATE -> LocalDate.parse(node.textValue().trim());
                        case TIME -> parseTime(node.textValue().trim(), true);
                        default -> TimeRange.parse(node.textValue().trim(), true);
                    };
                } catch (IllegalArgumentException | DateTimeParseException e) {
                    throw new BadValueException(formMessage());
                }
            }
        }
    }

    /** The value as the API returns it: numbers and booleans as such, the rest as text. */
    public Object toJson(Object value) {
        if (value == null) {
            return null;
        }
        return switch (this) {
            case DECIMAL, INTEGER, BOOLEAN -> value;
            default -> toText(value);
        };
    }

    public String formMessage() {
        return switch (this) {
            case DECIMAL -> "Must be a number.";
            case INTEGER -> "Must be a whole number.";
            case BOOLEAN -> "Must be true or false.";
            case DATE -> "Must be a date like 2026-10-12.";
            case TIME -> "Must be a time like 12:30.";
            case TIME_RANGE -> "Must be a time range like 06:00-15:00.";
        };
    }

    private static Boolean parseBoolean(String text) {
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new IllegalArgumentException(text);
    }

    /** strict: exactly HH:mm (the API). Otherwise anything LocalTime.parse takes (configuration, as before T0-4a). */
    static LocalTime parseTime(String text, boolean strict) {
        if (strict && !STRICT_TIME.matcher(text).matches()) {
            throw new IllegalArgumentException(text);
        }
        return LocalTime.parse(text);
    }
}
