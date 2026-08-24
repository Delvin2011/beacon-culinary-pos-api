package com.beaconculinary.api.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateStockTakeRequest {
    @NotNull(message = "locationId is required")
    private Long locationId;

    @NotEmpty(message = "lines must contain at least one item")
    @Valid
    private List<CreateStockTakeLineRequest> lines;
}
