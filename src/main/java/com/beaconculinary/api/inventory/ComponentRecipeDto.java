package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.ComponentCategoryRefDto;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ComponentRecipeDto {
    private Long componentCatalogId;
    private String componentName;
    private BigDecimal extraPrice;
    private ComponentCategoryRefDto category;
    private Integer batchSize;
    private List<RecipeLineDto> lines;
}
