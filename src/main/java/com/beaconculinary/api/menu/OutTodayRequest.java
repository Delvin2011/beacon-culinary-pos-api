package com.beaconculinary.api.menu;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OutTodayRequest {
    @NotNull(message = "out is required")
    private Boolean out;
}
