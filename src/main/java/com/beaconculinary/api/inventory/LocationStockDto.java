package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class LocationStockDto {
    private Long locationId;
    private String locationName;
    private BigDecimal stock;
}
