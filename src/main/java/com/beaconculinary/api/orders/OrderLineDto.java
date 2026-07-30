package com.beaconculinary.api.orders;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderLineDto {
    private Long id;
    private Long dailyMealOptionId;
    private String name;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal lineTotal;
    private List<OrderLineExtraDto> extras;
}
