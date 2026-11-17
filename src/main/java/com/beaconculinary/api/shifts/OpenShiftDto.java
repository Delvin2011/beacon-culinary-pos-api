package com.beaconculinary.api.shifts;

import com.beaconculinary.api.users.UserRefDto;

import java.math.BigDecimal;
import java.time.Instant;

/** POS Oversight B2: GET /shifts/open — this till's open shift, whoever owns it (O2: with the till). */
public record OpenShiftDto(Long shiftId, UserRefDto owner, boolean ownedByCaller, Instant openedAt, BigDecimal openingFloat,
                           String tillCode, String tillName) {
}
