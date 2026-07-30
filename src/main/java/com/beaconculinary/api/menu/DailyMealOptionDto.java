package com.beaconculinary.api.menu;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class DailyMealOptionDto {
    private Long id;
    private Long mealPeriodId;
    private LocalDate optionDate;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer plannedPortions;
    private Integer portionsRemaining;
}
