package com.beaconculinary.api.oversight;

import com.beaconculinary.api.users.UserRefDto;

import java.math.BigDecimal;
import java.time.Instant;

/** POS Oversight A2: one row of GET /admin/shifts. closedAt, closingCash and variance are null
 * while the shift is open; expectedCash is then live rather than the stored snapshot. */
public record ShiftListRowDto(
        Long shiftId,
        UserRefDto owner,
        ShiftDisplayStatus displayStatus,
        Instant openedAt,
        Instant closedAt,
        BigDecimal openingFloat,
        BigDecimal closingCash,
        BigDecimal expectedCash,
        BigDecimal variance,
        long orderCount) {
}
