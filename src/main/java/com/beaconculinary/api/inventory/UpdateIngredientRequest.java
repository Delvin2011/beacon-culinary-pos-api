package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateIngredientRequest {
    @NotBlank(message = "name is required")
    private String name;

    @NotNull(message = "unit is required")
    private IngredientUnit unit;

    @NotNull(message = "countSheetCategory is required")
    private CountSheetCategory countSheetCategory;

    private boolean active;
}
