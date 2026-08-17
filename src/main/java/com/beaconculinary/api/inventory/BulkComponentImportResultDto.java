package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class BulkComponentImportResultDto {
    private int componentsCreated;
    private int componentsUpdated;
    private int ingredientsCreated;
    private int ingredientsUpdated;
    private List<ComponentRecipeDto> components;
}
