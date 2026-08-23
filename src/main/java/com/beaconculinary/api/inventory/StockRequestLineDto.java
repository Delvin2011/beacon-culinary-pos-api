package com.beaconculinary.api.inventory;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockRequestLineDto {
    private Long id;
    private Long ingredientId;
    private String ingredientName;
    private BigDecimal requestedQuantity;
    private BigDecimal actionedQuantity;
    private String reason;
}
