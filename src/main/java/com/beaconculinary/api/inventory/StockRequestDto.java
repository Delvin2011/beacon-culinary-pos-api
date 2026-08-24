package com.beaconculinary.api.inventory;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class StockRequestDto {
    private Long id;
    private StockRequestType requestType;
    private StockRequestSource source;
    private Long requestedById;
    private String requestedByName;
    private LocalDateTime requestedAt;
    private StockRequestStatus status;
    private Long actionedById;
    private LocalDateTime actionedAt;
    private LocalDate dailyPlanDate;
    private Long dailyPlanPeriodId;
    // Stage 5.2.3 — set once an ORDER-type request has been approved into a PurchaseOrder, so
    // the frontend can link straight to it. Always null for ISSUE/WASTE.
    private Long purchaseOrderId;
    // Stage 5.2.4 — set for WASTE only; always null for ISSUE/ORDER.
    private Long locationId;
    private String locationName;
    private List<StockRequestLineDto> lines;
}
