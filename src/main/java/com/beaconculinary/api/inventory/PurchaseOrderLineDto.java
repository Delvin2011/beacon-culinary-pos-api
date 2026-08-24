package com.beaconculinary.api.inventory;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PurchaseOrderLineDto {
    private Long id;
    private Long ingredientId;
    private String ingredientName;
    private BigDecimal quantity;
    private String note;
}
