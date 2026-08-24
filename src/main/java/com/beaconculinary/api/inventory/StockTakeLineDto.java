package com.beaconculinary.api.inventory;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockTakeLineDto {
    private Long id;
    private Long ingredientId;
    private String ingredientName;
    private BigDecimal expectedQuantity;
    private BigDecimal actualQuantity;
    private BigDecimal unitCost;
    private BigDecimal varianceQuantity;
    private BigDecimal varianceValue;
    private BigDecimal appliedAdjustmentQuantity;
}
