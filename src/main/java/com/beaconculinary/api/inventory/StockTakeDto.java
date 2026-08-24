package com.beaconculinary.api.inventory;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class StockTakeDto {
    private Long id;
    private Long locationId;
    private String locationName;
    private Long submittedById;
    private String submittedByName;
    private LocalDateTime submittedAt;
    private StockTakeStatus status;
    private Long reviewedById;
    private LocalDateTime reviewedAt;
    private String note;
    private List<StockTakeLineDto> lines;
}
