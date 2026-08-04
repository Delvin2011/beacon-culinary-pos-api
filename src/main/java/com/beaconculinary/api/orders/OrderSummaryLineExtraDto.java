package com.beaconculinary.api.orders;

import lombok.Data;

@Data
public class OrderSummaryLineExtraDto {
    private String componentName;
    private Integer quantity;
}
