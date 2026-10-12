package com.beaconculinary.api.orders;

import com.beaconculinary.api.users.UserRefDto;
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
    @Mapping(target = "accountId", source = "account.id")
    OrderAdjustmentDto toDto(OrderAdjustment adjustment);

    @Mapping(target = "accountId", source = "account.id")
    OrderPaymentDto toDto(OrderPayment payment);

    @Mapping(target = "shiftId", source = "shift.id")
    @Mapping(target = "cashierId", source = "cashier.id")
    OrderDto toDto(Order order);

    default OrderDiscountDto toDto(OrderDiscount discount) {
        if (discount == null) {
            return null;
        }
        return new OrderDiscountDto(discount.getDiscountType(), discount.getDiscountValue(), discount.getAmount(),
                discount.getReasonCode(), discount.getNote(), UserRefDto.of(discount.getAuthorizedBy()));
    }
}
