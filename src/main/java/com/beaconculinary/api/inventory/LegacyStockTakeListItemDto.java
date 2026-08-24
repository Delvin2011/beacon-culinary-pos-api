package com.beaconculinary.api.inventory;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** @deprecated see {@link LegacyStockTake}. */
@Deprecated
@Data
public class LegacyStockTakeListItemDto {
    private Long id;
    private Long ingredientId;
    private String ingredientName;
    private BigDecimal countedQuantity;
    private BigDecimal expectedQuantity;
    private BigDecimal variance;
    private String note;
    private String recordedBy;
    private LocalDateTime createdAt;
}
