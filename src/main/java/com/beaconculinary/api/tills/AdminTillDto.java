package com.beaconculinary.api.tills;

import com.beaconculinary.api.offline.OfflineStatusDtos;

import java.time.Instant;

/**
 * O2: one row of GET /admin/tills. An active till, or the unregistered till while it has an open
 * shift (deviceId and tillCode null, registered false). {@code offline}: the till's latest status report
 * (O3); null for the unregistered till.
 */
public record AdminTillDto(String deviceId, String tillCode, String tillName, boolean registered, Shift shift,
                           OfflineStatusDtos.TillOffline offline) {

    /**
     * The till's open shift. {@code locked}: the owner has no active POS session on this till; null
     * for the unregistered till, which has no lock rule.
     */
    public record Shift(long shiftId, long ownerId, String ownerName, Instant openedAt, Boolean locked) {
    }
}
