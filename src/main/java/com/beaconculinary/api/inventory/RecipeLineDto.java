package com.beaconculinary.api.inventory;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class RecipeLineDto {
    private Long ingredientId;
    private String ingredientName;
    private IngredientUnit unit;
    private BigDecimal quantity;
}
