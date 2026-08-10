package com.beaconculinary.api.accounts;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateAccountRequest {
    @NotBlank(message = "name is required")
    private String name;

    @Email(message = "contactEmail must be a valid email address")
    private String contactEmail;
}
