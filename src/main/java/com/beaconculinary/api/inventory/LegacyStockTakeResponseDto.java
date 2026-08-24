package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** @deprecated see {@link LegacyStockTake}. */
@Deprecated
@Data
@AllArgsConstructor
public class LegacyStockTakeResponseDto {
    private Long id;
    private BigDecimal variance;
}
