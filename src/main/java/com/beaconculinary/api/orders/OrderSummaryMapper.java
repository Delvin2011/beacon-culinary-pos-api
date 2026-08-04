package com.beaconculinary.api.orders;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderSummaryMapper {
    @Mapping(target = "componentName", source = "dailyComponentStock.componentCatalog.name")
    OrderSummaryLineExtraDto toDto(OrderLineExtra extra);

    @Mapping(target = "optionName", source = "dailyMealOption.name")
    OrderSummaryLineDto toDto(OrderLine line);

    @Mapping(target = "orderId", source = "id")
    OrderSummaryDto toDto(Order order);
}
