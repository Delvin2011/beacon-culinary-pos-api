package com.beaconculinary.api.shifts;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OpenShiftRequest {
    @NotNull(message = "openingFloat is required")
    @DecimalMin(value = "0.00", message = "openingFloat cannot be negative")
    private BigDecimal openingFloat;
}
