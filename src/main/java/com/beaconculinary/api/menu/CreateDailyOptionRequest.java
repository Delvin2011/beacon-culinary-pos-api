package com.beaconculinary.api.menu;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateDailyOptionRequest {
    @NotNull(message = "mealPeriodId is required")
    private Long mealPeriodId;

    @NotNull(message = "optionDate is required")
    private LocalDate optionDate;

    @NotNull(message = "mealCatalogId is required")
    private Long mealCatalogId;

    @NotNull(message = "plannedPortions is required")
    @PositiveOrZero(message = "plannedPortions cannot be negative")
    private Integer plannedPortions;
}
