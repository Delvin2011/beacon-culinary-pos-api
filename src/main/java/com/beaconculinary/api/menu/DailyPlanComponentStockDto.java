package com.beaconculinary.api.menu;

import lombok.Data;

import java.math.BigDecimal;

/** Component stock as the admin planning page sees it — every status, with sold/remaining. */
@Data
public class DailyPlanComponentStockDto {
    private Long id;
    private String componentName;
    private BigDecimal extraPrice;
    private DailyPlanItemStatus status;
    private Integer bufferQuantity;
    private Integer actualQuantity;
    private int sold;
    private Integer bufferRemaining;
}
