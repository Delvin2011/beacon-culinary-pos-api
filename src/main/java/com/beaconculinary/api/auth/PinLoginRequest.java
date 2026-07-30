package com.beaconculinary.api.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class PinLoginRequest {
    @NotNull(message = "cashierId is required")
    private Long cashierId;

    @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4-6 digits")
    private String pin;
}
