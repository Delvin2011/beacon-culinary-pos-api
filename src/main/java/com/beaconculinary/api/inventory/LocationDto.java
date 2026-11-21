package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LocationDto {
    private Long id;
    private String name;
    // T0-5c: PRIMARY (receives GRVs, supplies issues) or SECONDARY; pickers order by sortOrder.
    private LocationKind kind;
    private int sortOrder;
}
