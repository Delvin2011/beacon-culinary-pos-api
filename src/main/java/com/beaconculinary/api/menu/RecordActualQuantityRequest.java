package com.beaconculinary.api.menu;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class RecordActualQuantityRequest {
    @NotNull(message = "actualQuantity is required")
    @PositiveOrZero(message = "actualQuantity cannot be negative")
    private Integer actualQuantity;
}
