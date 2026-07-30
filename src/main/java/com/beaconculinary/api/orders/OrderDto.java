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
    private PaymentMethod paymentMethod;
    private BigDecimal amountTendered;
    private BigDecimal changeDue;
    private BigDecimal subtotal;
    private BigDecimal total;
    private LocalDateTime createdAt;
    private boolean printFailed;
    private List<OrderLineDto> lines;
}
