package com.beaconculinary.api.shifts;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ShiftSummaryDto {
    private BigDecimal openingFloat;
    private BigDecimal cashSalesTotal;
    private BigDecimal adjustmentsTotal;
    private BigDecimal expectedCash;
    private long orderCount;
}
