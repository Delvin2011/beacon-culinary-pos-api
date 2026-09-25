package com.beaconculinary.api.menu;

import lombok.Data;

import java.math.BigDecimal;

/** A meal option as the admin planning page sees it — every status, with sold/remaining. */
@Data
public class DailyPlanOptionDto {
    private Long id;
    private String name;
    private BigDecimal price;
    private DailyPlanItemStatus status;
    private Integer plannedPortions;
    private Integer actualPortions;
    private int sold;
    private Integer portionsRemaining;
}
