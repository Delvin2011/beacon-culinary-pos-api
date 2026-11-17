package com.beaconculinary.api.tills;

import java.time.Instant;

/**
 * O2: one row of GET /admin/tills. An active till, or the unregistered till while it has an open
 * shift (deviceId and tillCode null, registered false).
 */
public record AdminTillDto(String deviceId, String tillCode, String tillName, boolean registered, Shift shift) {

    /**
     * The till's open shift. {@code locked}: the owner has no active POS session on this till; null
     * for the unregistered till, which has no lock rule.
     */
    public record Shift(long shiftId, long ownerId, String ownerName, Instant openedAt, Boolean locked) {
    }
}
