package com.beaconculinary.api.menu;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class DailyComponentStockDto {
    private Long id;
    private Long componentCatalogId;
    private String componentName;
    private Long mealPeriodId;
    private LocalDate optionDate;
    private BigDecimal extraPrice;
    private Integer bufferQuantity;
    private Integer bufferRemaining;
}
