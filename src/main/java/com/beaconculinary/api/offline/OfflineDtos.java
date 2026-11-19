package com.beaconculinary.api.offline;

import com.beaconculinary.api.menu.CatalogGroup;
import com.beaconculinary.api.menu.DailyPlanItemStatus;
import com.beaconculinary.api.menu.Station;
import com.beaconculinary.api.orders.PaymentMethod;
import com.beaconculinary.api.receipts.ReceiptSettingsDtos.PosView;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** O3: request and response shapes of the offline endpoints (contract sections 5, 8 and 9). */
public final class OfflineDtos {
    private OfflineDtos() {
    }

    /** POST /pos/offline/grant, 201. accountCap only when accounts are enabled offline. */
    public record GrantResponse(String grantId, String grant, String deviceId, String tillCode, long shiftId,
                                long cashierId, String cashierName, Instant issuedAt, Instant expiresAt,
                                LocalDate tradingDay, int nextOfflineSeq, int maxOfflineHours, int warnOfflineHours,
                                boolean accountsEnabled,
                                @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal accountCap,
                                Instant serverTimeUtc) {
    }

    // ---- GET /pos/offline/snapshot (contract section 8) ----

    public record SnapshotPeriod(long id, String name, LocalTime startTime, LocalTime endTime, boolean allDay) {
    }

    /** periodId null for beverages; portions null for always-on items (unlimited). */
    public record SnapshotOption(long id, String name, Station station, CatalogGroup group, Long periodId,
                                 BigDecimal price, DailyPlanItemStatus status, Integer plannedPortions,
                                 Integer portionsRemaining, boolean outToday, boolean alwaysOn) {
    }

    public record SnapshotExtra(long id, Long periodId, String componentName, BigDecimal priceDelta,
                                Integer bufferRemaining) {
    }

    /** id, number and name only (POPIA): no department, phone or email. */
    public record SnapshotAccount(long id, String accountNumber, String name) {
    }

    public record Limits(int maxOfflineHours, int warnOfflineHours) {
    }

    /** What the snapshot's version is a hash of: everything except generatedAt and serverTimeUtc. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SnapshotContent(LocalDate tradingDay, List<SnapshotPeriod> periods, List<SnapshotOption> options,
                                  List<SnapshotExtra> extras, BigDecimal vatRate, PosView receiptSettings,
                                  List<PaymentMethod> tenders, boolean accountsEnabled, BigDecimal accountCap,
                                  List<SnapshotAccount> accounts, Limits limits) {
    }

    /** accountCap and accounts only when accounts are enabled offline. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Snapshot(String snapshotVersion, Instant generatedAt, Instant serverTimeUtc, LocalDate tradingDay,
                           List<SnapshotPeriod> periods, List<SnapshotOption> options, List<SnapshotExtra> extras,
                           BigDecimal vatRate, PosView receiptSettings, List<PaymentMethod> tenders,
                           boolean accountsEnabled, BigDecimal accountCap, List<SnapshotAccount> accounts,
                           Limits limits) {
        static Snapshot of(String version, Instant now, SnapshotContent content) {
            return new Snapshot(version, now, now, content.tradingDay(), content.periods(), content.options(),
                    content.extras(), content.vatRate(), content.receiptSettings(), content.tenders(),
                    content.accountsEnabled(), content.accountCap(), content.accounts(), content.limits());
        }
    }
}
