package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class BulkIngredientImportResultDto {
    private int created;
    private int updated;
    private List<IngredientDto> ingredients;
}
