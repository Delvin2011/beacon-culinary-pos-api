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

    // WASTE: required, any active location. ISSUE (T0-5c): the requesting location, an active
    // SECONDARY; omitted = the production location (or, on an edit, the request's current one).
    // ORDER: ignored (not location-specific). Validated in the service, since it depends on requestType.
    private Long locationId;

    @NotEmpty(message = "lines must contain at least one item")
    @Valid
    private List<CreateStockRequestLineRequest> lines;
}
