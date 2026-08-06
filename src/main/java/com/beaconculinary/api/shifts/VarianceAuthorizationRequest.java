package com.beaconculinary.api.shifts;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VarianceAuthorizationRequest {
    @NotNull(message = "reasonCode is required")
    private ShiftVarianceReasonCode reasonCode;

    // Required only when reasonCode = OTHER — enforced in ShiftService, since that's a
    // cross-field rule a bean-validation annotation can't express cleanly.
    private String note;

    @NotBlank(message = "authorizationToken is required")
    private String authorizationToken;
}
