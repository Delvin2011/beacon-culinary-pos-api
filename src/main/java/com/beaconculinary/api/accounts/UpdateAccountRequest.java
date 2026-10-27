package com.beaconculinary.api.accounts;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** The PM account number is system-generated and cannot be changed. */
@Data
public class UpdateAccountRequest {
    @NotBlank(message = "name is required")
    @Size(max = 100, message = "name must be at most 100 characters")
    private String name;

    @NotBlank(message = "department is required")
    @Size(max = 100, message = "department must be at most 100 characters")
    private String department;

    @NotBlank(message = "contactNumber is required")
    @Pattern(regexp = "^\\+?[0-9 ()-]{7,20}$", message = "contactNumber must be a valid phone number")
    private String contactNumber;

    @NotBlank(message = "contactEmail is required")
    @Email(message = "contactEmail must be a valid email address")
    @Size(max = 255, message = "contactEmail must be at most 255 characters")
    private String contactEmail;

    private boolean active = true;
}
