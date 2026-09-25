package com.beaconculinary.api.inventory;

import lombok.Data;

@Data
public class IngredientDto {
    private Long id;
    private String name;
    private IngredientUnit unit;
    private Long countSheetCategoryId;
    private String countSheetCategoryName;
    private boolean active;
    private String itemCode;
}
