package com.beaconculinary.api.orders;

import lombok.Data;

import java.util.List;

@Data
public class OrderSummaryLineDto {
    private String optionName;
    private Integer quantity;
    private List<OrderSummaryLineExtraDto> extras;
}
