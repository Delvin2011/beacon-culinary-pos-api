package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** @deprecated see {@link LegacyStockTake}. */
@Deprecated
@Data
@AllArgsConstructor
public class LegacyStockTakeListResponseDto {
    private List<LegacyStockTakeListItemDto> entries;
}
