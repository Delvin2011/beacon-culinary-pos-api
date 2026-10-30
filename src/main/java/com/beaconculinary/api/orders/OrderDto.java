package com.beaconculinary.api.orders;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderDto {
    private Long id;
    private Integer orderNumber;
    private LocalDate orderDate;
    private Long shiftId;
    private Long cashierId;
    private OrderStatus status;
    private OrderKind orderKind;
    private BigDecimal subtotal;
    private BigDecimal total;
    private BigDecimal originalTotal;
    private BigDecimal preDiscountTotal;
    private BigDecimal saleDiscount;
    // null when no discount was given at sale.
    private OrderDiscountDto discount;
    // null for a sale.
    private OrderComplimentaryDto complimentary;
    private LocalDateTime createdAt;
    private boolean printFailed;
    private List<OrderLineDto> lines;
    // One per station the order needs (KITCHEN, BAR); empty for a cold-only order.
    private List<OrderStationDto> stations;
    private List<OrderAdjustmentDto> adjustments;
    private List<OrderPaymentDto> payments;
}
