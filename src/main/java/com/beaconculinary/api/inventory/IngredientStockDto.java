package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class IngredientStockDto {
    private BigDecimal currentStock;
    private LocalDateTime lastMovementAt;
}
