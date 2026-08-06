package com.beaconculinary.api.shifts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CloseShiftRequest {
    @NotNull(message = "countedCash is required")
    @DecimalMin(value = "0.00", message = "countedCash cannot be negative")
    private BigDecimal countedCash;

    // Omitted entirely when countedCash == expectedCash — required only for a nonzero
    // variance, enforced in ShiftService.
    @Valid
    private VarianceAuthorizationRequest varianceAuthorization;
}
