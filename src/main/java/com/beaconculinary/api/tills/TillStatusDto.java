package com.beaconculinary.api.tills;

import java.time.Instant;

/**
 * O2 R12: GET /pos/till-status, what the POS sign-in screen shows. Till and shift details only for a
 * registered till; any other browser gets registered=false and shift=null.
 */
public record TillStatusDto(boolean registered, String tillCode, String tillName, Shift shift, int idleLockMinutes,
                            boolean requireRegistered, boolean offlineEnabled, int maxOfflineHours,
                            int warnOfflineHours) {

    /** {@code locked}: the owner has no active POS session on this till. */
    public record Shift(long shiftId, long ownerId, String ownerName, Instant openedAt, boolean locked) {
    }
}
