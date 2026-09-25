package com.beaconculinary.api.menu;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class RecordActualPortionsRequest {
    @NotNull(message = "actualPortions is required")
    @PositiveOrZero(message = "actualPortions cannot be negative")
    private Integer actualPortions;
}
