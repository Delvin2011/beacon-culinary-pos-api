package com.beaconculinary.api.orders;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderLineExtraDto {
    private Long id;
    private Long dailyComponentStockId;
    private String componentName;
    private BigDecimal priceDelta;
    private Integer quantity;
    private BigDecimal lineTotal;
    private boolean adjusted;
}
