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
    // The ledger cost: the VAT-inclusive unit cost (4 dp), whatever basis the invoice used.
    private BigDecimal costPerUnit;
    // quantityReceived - quantityOrdered, computed at read time. Null when quantityOrdered is
    // null (an ad-hoc line, not linked to any purchase order line) — there's nothing to vary
    // against. Negative = short delivery, positive = over-delivery.
    private BigDecimal receiptVariance;

    // GRV VAT handling. unitPrice is as written on the invoice. On legacy lines (captured before
    // VAT handling) vatCode, vatRate, unitCostExclVat, lineExclVat and lineVat are null.
    private BigDecimal unitPrice;
    private VatCode vatCode;
    private BigDecimal vatRate;
    private BigDecimal unitCostExclVat;
    private BigDecimal unitCostInclVat;
    private BigDecimal lineExclVat;
    private BigDecimal lineVat;
    private BigDecimal lineInclVat;
}
