package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateIngredientRequest {
    @NotBlank(message = "name is required")
    private String name;

    @NotNull(message = "unit is required")
    private IngredientUnit unit;

    @NotNull(message = "countSheetCategoryId is required")
    private Long countSheetCategoryId;

    private String itemCode;
    // GRV VAT handling: optional; STANDARD when a new ingredient leaves it out, unchanged on update.
    private VatCode defaultVatCode;
}
