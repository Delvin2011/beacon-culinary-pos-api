package com.beaconculinary.api.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ConfirmIngredientRequirementsRequest {
    @NotBlank(message = "period is required")
    private String period;

    @Valid
    private List<IngredientRequirementAdjustmentRequest> adjustments = List.of();
}
