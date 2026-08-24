package com.beaconculinary.api.inventory;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class GrvDto {
    private Long id;
    private String invoiceNumber;
    private Long purchaseOrderId;
    private String supplierName;
    private String note;
    private Long receivedById;
    private LocalDateTime receivedAt;
    private List<GrvLineDto> lines;
}
