package com.beaconculinary.api.menu;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MealCatalogDto {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private boolean active;
    private List<ComponentCatalogDto> components;
}
