package com.beaconculinary.api.inventory;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PurchaseOrderDto {
    private Long id;
    private String supplierName;
    private PurchaseOrderStatus status;
    private LocalDateTime createdAt;
    private List<PurchaseOrderLineDto> lines;
}
