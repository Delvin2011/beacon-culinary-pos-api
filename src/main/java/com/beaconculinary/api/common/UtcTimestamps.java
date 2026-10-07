package com.beaconculinary.api.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** DATETIME2 columns hold UTC wall-clock values (SYSUTCDATETIME() defaults), mapped as
 * LocalDateTime. These helpers convert at the edges so new endpoints emit "...Z" instants and
 * code never writes the business-zone clock's local time into a UTC column. */
public final class UtcTimestamps {
    private UtcTimestamps() {
    }

    public static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    public static LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static LocalDateTime nowUtc(Clock clock) {
        return toUtc(clock.instant());
    }
}
