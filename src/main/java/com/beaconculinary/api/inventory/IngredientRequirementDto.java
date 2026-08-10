package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class IngredientRequirementDto {
    private Long ingredientId;
    private String name;
    private IngredientUnit unit;
    private BigDecimal calculatedQuantity;
    private BigDecimal currentStock;
}
