package com.beaconculinary.api.orders;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderMapper {
    @Mapping(target = "dailyComponentStockId", source = "dailyComponentStock.id")
    @Mapping(target = "componentName", source = "dailyComponentStock.componentCatalog.name")
    OrderLineExtraDto toDto(OrderLineExtra extra);

    @Mapping(target = "dailyMealOptionId", source = "dailyMealOption.id")
    @Mapping(target = "name", source = "dailyMealOption.name")
    OrderLineDto toDto(OrderLine line);

    @Mapping(target = "requestedById", source = "requestedBy.id")
    @Mapping(target = "authorizedById", source = "authorizedBy.id")
    OrderAdjustmentDto toDto(OrderAdjustment adjustment);

    @Mapping(target = "shiftId", source = "shift.id")
    @Mapping(target = "cashierId", source = "cashier.id")
    OrderDto toDto(Order order);
}
