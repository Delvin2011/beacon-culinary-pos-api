package com.beaconculinary.api.shifts;

import com.beaconculinary.api.users.UserRefDto;

import java.math.BigDecimal;
import java.time.Instant;

/** POS Oversight B2: GET /shifts/open — the till's open shift, whoever owns it. */
public record OpenShiftDto(Long shiftId, UserRefDto owner, boolean ownedByCaller, Instant openedAt, BigDecimal openingFloat) {
}
