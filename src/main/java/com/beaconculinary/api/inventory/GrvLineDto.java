package com.beaconculinary.api.inventory;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class GrvLineDto {
    private Long id;
    private Long ingredientId;
    private String ingredientName;
    private Long purchaseOrderLineId;
    private BigDecimal quantityOrdered;
    private BigDecimal quantityReceived;
    private BigDecimal costPerUnit;
    // quantityReceived - quantityOrdered, computed at read time. Null when quantityOrdered is
    // null (an ad-hoc line, not linked to any purchase order line) — there's nothing to vary
    // against. Negative = short delivery, positive = over-delivery.
    private BigDecimal receiptVariance;
}
