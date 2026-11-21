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
    // T0-5c: so the screen can mark the primary and a deactivated location still holding stock.
    private LocationKind kind;
    private boolean active;
}
