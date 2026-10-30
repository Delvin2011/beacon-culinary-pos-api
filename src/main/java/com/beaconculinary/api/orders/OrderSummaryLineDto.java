package com.beaconculinary.api.orders;

import com.beaconculinary.api.menu.Station;
import lombok.Data;

import java.util.List;

@Data
public class OrderSummaryLineDto {
    private String optionName;
    private Integer quantity;
    private Station station;
    private List<OrderSummaryLineExtraDto> extras;
}
