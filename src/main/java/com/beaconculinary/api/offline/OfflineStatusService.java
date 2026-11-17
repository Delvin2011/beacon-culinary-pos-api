package com.beaconculinary.api.offline;

import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.offline.OfflineStatusDtos.State;
import com.beaconculinary.api.offline.OfflineStatusDtos.StatusAck;
import com.beaconculinary.api.offline.OfflineStatusDtos.StatusReport;
import com.beaconculinary.api.offline.OfflineStatusDtos.TillOffline;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * O3: till status reports (contract section 9) and what they mean (10.2). Accepted whether offline
 * is enabled or not. A report older than pos.offline.status-stale-minutes is UNKNOWN: its counts are
 * still the last thing the till said, so they keep blocking (step 6).
 */
@Service
public class OfflineStatusService {
    private final OfflineProperties properties;
    private final OfflineAccess access;
    private final OfflineStatusStore store;
    private final Clock clock;

    public OfflineStatusService(OfflineProperties properties, OfflineAccess access, OfflineStatusStore store, Clock clock) {
        this.properties = properties;
        this.access = access;
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public StatusAck report(StatusReport report) {
        var till = access.requireTill();
        var receivedAt = clock.instant();
        var skewMs = Duration.between(receivedAt, report.tillClockUtc()).toMillis();
        store.save(new OfflineStatusStore.StatusRow(till.deviceId(), report.pendingCount(), utc(report.oldestPendingSoldAt()),
                utc(report.lastSyncAt()), utc(report.offlineSince()), utc(report.tillClockUtc()), utc(receivedAt), skewMs,
                blankToNull(report.appVersion())), till.credentialId(), report.pending());
        return new StatusAck(receivedAt, skewMs, receivedAt);
    }

    /** Each till that has reported, by device id: its latest report and its state now. */
    @Transactional(readOnly = true)
    public Map<UUID, TillOffline> forAdmin() {
        var now = UtcTimestamps.nowUtc(clock);
        var byDevice = new HashMap<UUID, TillOffline>();
        for (var row : store.findAll()) {
            byDevice.put(row.deviceId(), new TillOffline(state(row.pendingCount(), row.receivedAt(), now), row.pendingCount(),
                    UtcTimestamps.toInstant(row.receivedAt()), row.clockSkewMs(), UtcTimestamps.toInstant(row.offlineSince()),
                    row.appVersion()));
        }
        return byDevice;
    }

    /** True when a report received at {@code receivedAt} (UTC) is older than status-stale-minutes. */
    public boolean stale(LocalDateTime receivedAt) {
        return stale(receivedAt, UtcTimestamps.nowUtc(clock));
    }

    private boolean stale(LocalDateTime receivedAt, LocalDateTime now) {
        return receivedAt.isBefore(now.minusMinutes(properties.getStatusStaleMinutes()));
    }

    private State state(int pendingCount, LocalDateTime receivedAt, LocalDateTime now) {
        if (stale(receivedAt, now)) {
            return State.UNKNOWN;
        }
        return pendingCount > 0 ? State.PENDING : State.OK;
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : UtcTimestamps.toUtc(instant);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
