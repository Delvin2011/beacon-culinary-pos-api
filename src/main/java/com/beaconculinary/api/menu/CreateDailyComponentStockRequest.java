package com.beaconculinary.api.menu;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateDailyComponentStockRequest {
    @NotNull(message = "componentCatalogId is required")
    private Long componentCatalogId;

    @NotNull(message = "mealPeriodId is required")
    private Long mealPeriodId;

    @NotNull(message = "optionDate is required")
    private LocalDate optionDate;

    @NotNull(message = "bufferQuantity is required")
    @PositiveOrZero(message = "bufferQuantity cannot be negative")
    private Integer bufferQuantity;
}
