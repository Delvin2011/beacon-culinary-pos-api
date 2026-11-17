package com.beaconculinary.api.offline;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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
}
