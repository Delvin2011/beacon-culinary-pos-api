package com.beaconculinary.api.staff;

import com.beaconculinary.api.common.UtcTimestamps;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * One short-term sign-in block, as the staff page shows it (WS1-1). A block ends by itself when
 * its failures leave the 15-minute window; an unlock or a successful sign-in also ends it.
 *
 * @param blocked      a block is active now
 * @param blockedUntil when it ends (UTC) if nothing else happens; null when not blocked
 */
public record AccountBlockDto(boolean blocked, Instant blockedUntil) {
    static final AccountBlockDto NOT_BLOCKED = new AccountBlockDto(false, null);

    static AccountBlockDto of(LocalDateTime until) {
        return until == null ? NOT_BLOCKED : new AccountBlockDto(true, UtcTimestamps.toInstant(until));
    }
}
