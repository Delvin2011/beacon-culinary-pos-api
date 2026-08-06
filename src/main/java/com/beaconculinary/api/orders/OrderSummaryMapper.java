package com.beaconculinary.api.orders;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface OrderSummaryMapper {
    @Mapping(target = "componentName", source = "dailyComponentStock.componentCatalog.name")
    OrderSummaryLineExtraDto toDto(OrderLineExtra extra);

    // Extras voided/refunded by a Stage 2.6 EXTRAS_ONLY adjustment are excluded here rather than
    // deleted from the order — this mapper is shared by GET /kitchen/orders and the SSE payloads
    // (ORDER_CREATED/STATUS_CHANGED/ORDER_UPDATED), so filtering here is enough to make the
    // kitchen ticket visually drop them everywhere at once, with no separate code path needed.
    @Mapping(target = "optionName", source = "dailyMealOption.name")
    @Mapping(target = "extras", expression = "java(toUnadjustedExtraDtos(line))")
    OrderSummaryLineDto toDto(OrderLine line);

    @Mapping(target = "orderId", source = "id")
    OrderSummaryDto toDto(Order order);

    default List<OrderSummaryLineExtraDto> toUnadjustedExtraDtos(OrderLine line) {
        return line.getExtras().stream()
                .filter(extra -> !extra.isAdjusted())
                .map(this::toDto)
                .toList();
    }
}
