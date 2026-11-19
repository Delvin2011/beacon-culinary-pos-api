package com.beaconculinary.api.offline;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** O3: the till status report (heartbeat) and what managers see of it (contract sections 9 and 10.2). */
public final class OfflineStatusDtos {
    static final int MAX_PENDING_ENTRIES = 20;

    private OfflineStatusDtos() {
    }

    /** POST /pos/offline/status. */
    public record StatusReport(
            @NotNull(message = "pendingCount is required") @Min(value = 0, message = "pendingCount cannot be negative")
            Integer pendingCount,
            Instant oldestPendingSoldAt,
            Instant lastSyncAt,
            Instant offlineSince,
            @NotNull(message = "tillClockUtc is required") Instant tillClockUtc,
            @Size(max = 50, message = "appVersion must be 50 characters or fewer") String appVersion,
            @NotNull(message = "pending is required (it may be empty)")
            @Size(max = MAX_PENDING_ENTRIES, message = "pending may have at most 20 entries")
            List<@Valid @NotNull(message = "pending entries can't be null") PendingEntry> pending) {

        @JsonIgnore
        @AssertTrue(message = "The pending counts must add up to pendingCount.")
        public boolean isPendingCountConsistent() {
            if (pendingCount == null || pending == null || pending.stream().anyMatch(e -> e == null || e.count() == null)) {
                return true;
            }
            return pending.stream().mapToInt(PendingEntry::count).sum() == pendingCount;
        }

        @JsonIgnore
        @AssertTrue(message = "pending may list each shift and trading day only once.")
        public boolean isPendingUnique() {
            if (pending == null) {
                return true;
            }
            var seen = new HashSet<List<Object>>();
            return pending.stream().filter(Objects::nonNull)
                    .allMatch(e -> seen.add(java.util.Arrays.asList(e.shiftId(), e.tradingDay())));
        }
    }

    /** Unsynced sales of one shift and trading day. */
    public record PendingEntry(
            @NotNull(message = "shiftId is required") Long shiftId,
            @NotNull(message = "tradingDay is required") LocalDate tradingDay,
            @NotNull(message = "count is required") @Min(value = 1, message = "count must be at least 1") Integer count,
            Instant oldestSoldAt) {
    }

    /** The reply: when the server received it, and the till's clock skew (positive: the till is ahead). */
    public record StatusAck(Instant receivedAt, long clockSkewMs, Instant serverTimeUtc) {
    }

    public enum State {
        NEVER_REPORTED, OK, PENDING, UNKNOWN
    }

    /** GET /admin/tills, per registered till: its latest report and what it means now. */
    public record TillOffline(State state, Integer pendingCount, Instant lastReportAt, Long clockSkewMs,
                              Instant offlineSince, String appVersion, int openExceptions) {
        public static final TillOffline NEVER = new TillOffline(State.NEVER_REPORTED, null, null, null, null, null, 0);

        /** O4: with the till's open REVIEW sale exceptions. */
        public TillOffline withOpenExceptions(int count) {
            return new TillOffline(state, pendingCount, lastReportAt, clockSkewMs, offlineSince, appVersion, count);
        }
    }
}
