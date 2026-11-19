package com.beaconculinary.api.offline;

import com.beaconculinary.api.common.UtcTimestamps;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * O3: unsynced offline sales as the tills last reported them (contract sections 10.3 and 10.4), for
 * Close Day and shift close. A report blocks whether it is fresh or stale: a till that went silent
 * after reporting pending sales may still hold them.
 */
@Component
public class OfflinePending {
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final OfflineStatusStore store;
    private final OfflineStatusService statusService;

    public OfflinePending(OfflineStatusStore store, OfflineStatusService statusService) {
        this.store = store;
        this.statusService = statusService;
    }

    /** One till's reported pending sales (for a day or a shift), and its last report. */
    public record TillPending(UUID deviceId, String tillCode, String tillName, int pendingCount, Instant lastReportAt,
                              boolean stale) {
        public String label() {
            var code = tillCode == null ? "A till" : tillCode;
            return tillName == null ? code : code + " (" + tillName + ")";
        }

        /** "last report 09:15" in the business zone, plus "nothing heard since" when stale. */
        public String lastReportText(ZoneId zone) {
            var time = HOUR_MINUTE.format(lastReportAt.atZone(zone));
            return "last report " + time + (stale ? ", nothing heard since" : "");
        }

        public Map<String, Object> detail() {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("deviceId", deviceId.toString());
            detail.put("tillCode", tillCode);
            detail.put("tillName", tillName);
            detail.put("pendingCount", pendingCount);
            detail.put("lastReportAt", lastReportAt.toString());
            detail.put("stale", stale);
            return detail;
        }
    }

    /** Each till with pending sales for {@code tradingDay}, by till code. */
    public List<TillPending> forDay(LocalDate tradingDay) {
        return group(store.pendingForDay(tradingDay));
    }

    /** The pending sales the shift's own till reported for it, if any (none for the unregistered till). */
    public Optional<TillPending> forShift(long shiftId, UUID deviceId) {
        if (deviceId == null) {
            return Optional.empty();
        }
        return group(store.pendingForShift(shiftId).stream().filter(row -> deviceId.equals(row.deviceId())).toList())
                .stream().findFirst();
    }

    /** 409 OFFLINE_SALES_PENDING for a shift close (contract 10.4). */
    public static OfflineException shiftCloseRefused(TillPending pending) {
        var details = new LinkedHashMap<String, Object>();
        details.put("pendingCount", pending.pendingCount());
        details.put("lastReportAt", pending.lastReportAt().toString());
        details.put("stale", pending.stale());
        return new OfflineException(HttpStatus.CONFLICT, "OFFLINE_SALES_PENDING",
                "This till still has " + pending.pendingCount() + " unsynced " + sales(pending.pendingCount())
                        + " for this shift. Sync them before closing.", details);
    }

    public static String sales(int count) {
        return count == 1 ? "sale" : "sales";
    }

    private List<TillPending> group(List<OfflineStatusStore.PendingRow> rows) {
        var byDevice = new LinkedHashMap<UUID, List<OfflineStatusStore.PendingRow>>();
        for (var row : rows) {
            byDevice.computeIfAbsent(row.deviceId(), id -> new ArrayList<>()).add(row);
        }
        var result = new ArrayList<TillPending>();
        for (var entry : byDevice.entrySet()) {
            var first = entry.getValue().get(0);
            var count = entry.getValue().stream().mapToInt(OfflineStatusStore.PendingRow::pendingCount).sum();
            result.add(new TillPending(entry.getKey(), first.tillCode(), first.tillName(), count,
                    UtcTimestamps.toInstant(first.receivedAt()), statusService.stale(first.receivedAt())));
        }
        return result;
    }
}
