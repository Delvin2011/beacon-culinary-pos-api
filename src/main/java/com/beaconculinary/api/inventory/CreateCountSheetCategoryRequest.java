package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateCountSheetCategoryRequest {
    @NotBlank(message = "name is required")
    @Size(max = 50, message = "name must be at most 50 characters")
    private String name;
}
