package com.beaconculinary.api.admin;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AuthorizeRequest {
    @Pattern(regexp = "\\d{6}", message = "PIN must be 6 digits")
    private String pin;
}
