package com.beaconculinary.api.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

@Data
public class UpdateRecipeRequest {
    @NotNull(message = "batchSize is required")
    @Positive(message = "batchSize must be positive")
    private Integer batchSize;

    @Valid
    private List<RecipeLineRequest> lines = List.of();
}
