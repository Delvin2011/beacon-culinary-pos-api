package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class IngredientStockDto {
    private BigDecimal totalStock;
    private List<LocationStockDto> byLocation;
    private LocalDateTime lastMovementAt;
}
