package com.beaconculinary.api.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateStockRequestRequest {
    @NotNull(message = "requestType is required")
    private StockRequestType requestType;

    // Required for WASTE only (validated in the service, since it's conditional on requestType);
    // ignored for ISSUE (fixed Main Store -> Kitchen) and ORDER (not location-specific).
    private Long locationId;

    @NotEmpty(message = "lines must contain at least one item")
    @Valid
    private List<CreateStockRequestLineRequest> lines;
}
