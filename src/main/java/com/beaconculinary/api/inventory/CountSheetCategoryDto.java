package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CountSheetCategoryDto {
    private Long id;
    private String name;
    private boolean active;
}
