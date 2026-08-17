package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class StockTakeListResponseDto {
    private List<StockTakeListItemDto> entries;
}
