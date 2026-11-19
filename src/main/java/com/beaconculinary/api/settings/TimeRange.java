package com.beaconculinary.api.settings;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** T0-4a: a time-of-day range such as the dashboard's opening hours, written "06:00-15:00". */
public record TimeRange(LocalTime from, LocalTime to) {
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** "06:00-15:00" (an en dash is accepted too, as before T0-4a). Throws IllegalArgumentException. */
    public static TimeRange parse(String text, boolean strict) {
        var hours = text.split("[-–]");
        if (hours.length != 2) {
            throw new IllegalArgumentException("not a range: " + text);
        }
        return new TimeRange(SettingType.parseTime(hours[0].trim(), strict), SettingType.parseTime(hours[1].trim(), strict));
    }

    @Override
    public String toString() {
        return HH_MM.format(from) + "-" + HH_MM.format(to);
    }
}
