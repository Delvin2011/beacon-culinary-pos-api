package com.beaconculinary.api.orders;

import com.beaconculinary.api.menu.Station;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderLineDto {
    private Long id;
    // Where the line is prepared: KITCHEN, BAR, or NONE (cold drinks, handed over at payment).
    private Station station;
    private Long dailyMealOptionId;
    private String name;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal lineTotal;
    private List<OrderLineExtraDto> extras;
}
