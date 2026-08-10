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
    private BigDecimal subtotal;
    private BigDecimal total;
    private BigDecimal originalTotal;
    private LocalDateTime createdAt;
    private boolean printFailed;
    private List<OrderLineDto> lines;
    private List<OrderAdjustmentDto> adjustments;
    private List<OrderPaymentDto> payments;
}
