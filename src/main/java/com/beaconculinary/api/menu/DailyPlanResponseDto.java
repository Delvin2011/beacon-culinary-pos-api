package com.beaconculinary.api.menu;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class DailyPlanResponseDto {
    private List<DailyPlanOptionDto> options;
    private List<DailyPlanComponentStockDto> componentStock;
    // false for the period that takes light meals (All Day): always-on items, nothing to plan.
    private boolean plannable;
}
