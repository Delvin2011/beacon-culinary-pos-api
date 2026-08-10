package com.beaconculinary.api.inventory;

import lombok.Data;

import java.util.List;

@Data
public class RecipeDto {
    private Long componentCatalogId;
    private Integer batchSize;
    private List<RecipeLineDto> lines;
}
