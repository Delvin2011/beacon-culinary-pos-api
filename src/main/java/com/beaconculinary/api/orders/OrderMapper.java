package com.beaconculinary.api.orders;

import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.users.UserRefDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Comparator;
import java.util.List;

@Mapper(componentModel = "spring", imports = UserRefDto.class)
public interface OrderMapper {
    @Mapping(target = "dailyComponentStockId", source = "dailyComponentStock.id")
    @Mapping(target = "componentName", expression = "java(componentName(extra))")
    OrderLineExtraDto toDto(OrderLineExtra extra);

    @Mapping(target = "dailyMealOptionId", source = "dailyMealOption.id")
    @Mapping(target = "name", expression = "java(lineName(line))")
    OrderLineDto toDto(OrderLine line);

    @Mapping(target = "requestedById", source = "requestedBy.id")
    @Mapping(target = "authorizedById", source = "authorizedBy.id")
    @Mapping(target = "accountId", source = "account.id")
    OrderAdjustmentDto toDto(OrderAdjustment adjustment);

    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "authorizedBy", expression = "java(UserRefDto.of(payment.getAuthorizedBy()))")
    OrderPaymentDto toDto(OrderPayment payment);

    @Mapping(target = "shiftId", source = "shift.id")
    @Mapping(target = "receiptNumber", expression = "java(ReceiptNumbers.of(order.getOrderDate(), order.getOrderNumber()))")
    @Mapping(target = "tillCode", source = "shift.tillCode")
    @Mapping(target = "tillName", source = "shift.tillName")
    @Mapping(target = "cashierId", source = "cashier.id")
    @Mapping(target = "cashierName", source = "cashier.name")
    @Mapping(target = "stations", expression = "java(toStationDtos(order))")
    OrderDto toDto(Order order);

    /** O2b: the name stored at sale, or the option's current name for lines from before V109. */
    @Named("lineName")
    default String lineName(OrderLine line) {
        if (line.getName() != null) {
            return line.getName();
        }
        return line.getDailyMealOption() == null ? null : line.getDailyMealOption().getName();
    }

    /** O2b: the component name stored at sale, or the current one for extras from before V109. */
    @Named("componentName")
    default String componentName(OrderLineExtra extra) {
        if (extra.getComponentName() != null) {
            return extra.getComponentName();
        }
        var stock = extra.getDailyComponentStock();
        return stock == null || stock.getComponentCatalog() == null ? null : stock.getComponentCatalog().getName();
    }

    default List<OrderStationDto> toStationDtos(Order order) {
        return order.getStationStatuses().stream()
                .sorted(Comparator.comparing(OrderStationStatus::getStation))
                .map(row -> new OrderStationDto(row.getStation(), row.getStatus(),
                        UtcTimestamps.toInstant(row.getUpdatedAt()), UserRefDto.of(row.getUpdatedBy())))
                .toList();
    }

    default OrderDiscountDto toDto(OrderDiscount discount) {
        if (discount == null) {
            return null;
        }
        return new OrderDiscountDto(discount.getDiscountType(), discount.getDiscountValue(), discount.getAmount(),
                discount.getReasonCode(), discount.getNote(), UserRefDto.of(discount.getAuthorizedBy()));
    }

    default OrderComplimentaryDto toDto(OrderComplimentary complimentary) {
        if (complimentary == null) {
            return null;
        }
        return new OrderComplimentaryDto(complimentary.getReasonCode(), complimentary.getNote(),
                UserRefDto.of(complimentary.getAuthorizedBy()));
    }
}
