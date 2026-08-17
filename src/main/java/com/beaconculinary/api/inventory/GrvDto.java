package com.beaconculinary.api.inventory;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class GrvDto {
    private Long id;
    private Long ingredientId;
    private String ingredientName;
    private BigDecimal quantity;
    private BigDecimal costPerUnit;
    private String supplierName;
    private String note;
    private Long receivedById;
    private LocalDateTime receivedAt;
}
