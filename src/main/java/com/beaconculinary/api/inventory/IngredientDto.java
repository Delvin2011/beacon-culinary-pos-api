package com.beaconculinary.api.inventory;

import lombok.Data;

@Data
public class IngredientDto {
    private Long id;
    private String name;
    private IngredientUnit unit;
    private CountSheetCategory countSheetCategory;
    private boolean active;
    private String itemCode;
}
