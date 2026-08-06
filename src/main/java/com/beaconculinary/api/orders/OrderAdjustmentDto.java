package com.beaconculinary.api.orders;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderAdjustmentDto {
    private Long id;
    private OrderAdjustmentScope scope;
    private OrderAdjustmentAction action;
    private OrderAdjustmentReasonCode reasonCode;
    private String note;
    private BigDecimal amount;
    private Long requestedById;
    private Long authorizedById;
    private LocalDateTime createdAt;
}
