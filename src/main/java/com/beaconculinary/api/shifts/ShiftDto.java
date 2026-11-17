package com.beaconculinary.api.shifts;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class ShiftDto {
    private Long id;
    private Long cashierId;
    private BigDecimal openingFloat;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private ShiftStatus status;
    private BigDecimal closingCash;
    private BigDecimal expectedCash;
    private BigDecimal variance;
    private ShiftVarianceReasonCode varianceReasonCode;
    private String varianceNote;
    private Long varianceAuthorizedById;
    // O2: the till (null deviceId and tillCode = the unregistered till), as it was when the shift opened.
    private UUID deviceId;
    private String tillCode;
    private String tillName;
    private boolean forceClosed;
    private ShiftCashCount cashCount;
}
