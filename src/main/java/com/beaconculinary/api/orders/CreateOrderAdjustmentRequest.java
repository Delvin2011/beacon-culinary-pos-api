package com.beaconculinary.api.orders;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateOrderAdjustmentRequest {
    @NotNull(message = "scope is required")
    private OrderAdjustmentScope scope;

    @NotNull(message = "reasonCode is required")
    private OrderAdjustmentReasonCode reasonCode;

    // Required only when reasonCode = OTHER — enforced in OrderAdjustmentService, since that's
    // a cross-field rule a bean-validation annotation can't express cleanly.
    private String note;

    @NotBlank(message = "authorizationToken is required")
    private String authorizationToken;
}
