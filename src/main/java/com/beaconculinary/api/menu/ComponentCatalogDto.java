package com.beaconculinary.api.menu;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ComponentCatalogDto {
    private Long id;
    private String name;
    private BigDecimal extraPrice;
    private boolean active;
}
