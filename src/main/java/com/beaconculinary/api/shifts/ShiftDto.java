package com.beaconculinary.api.shifts;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
}
