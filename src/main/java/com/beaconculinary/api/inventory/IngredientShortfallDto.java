package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** One ingredient whose confirmed deduction would take derived stock below zero. Deduction still
 * proceeds — Stage 5's locked decision is warn, never block. */
@Data
@AllArgsConstructor
public class IngredientShortfallDto {
    private Long ingredientId;
    private String name;
    private IngredientUnit unit;
    private BigDecimal finalQuantity;
    private BigDecimal resultingStock;
}
