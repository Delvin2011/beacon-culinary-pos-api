package com.beaconculinary.api.inventory;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class WasteEntryDto {
    private Long id;
    private Long ingredientId;
    private String ingredientName;
    private Long locationId;
    private String locationName;
    private BigDecimal quantity;
    private String reason;
    private String note;
    private LocalDateTime createdAt;
}
