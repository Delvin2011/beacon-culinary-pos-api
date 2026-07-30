package com.beaconculinary.api.menu;

import lombok.Data;

import java.util.List;

@Data
public class MenuTodayResponseDto {
    private List<DailyMealOptionDto> options;
    private List<DailyComponentStockDto> availableExtras;
}
