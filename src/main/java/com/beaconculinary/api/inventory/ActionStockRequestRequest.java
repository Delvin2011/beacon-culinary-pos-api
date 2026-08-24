package com.beaconculinary.api.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ActionStockRequestRequest {
    // Required only for ORDER-type requests (validated in the service, since it's conditional on
    // requestType) — the clerk requesting an item doesn't necessarily know who it should be
    // ordered from; that's the approving STOCK_ADMIN's call.
    private String supplierName;

    @NotEmpty(message = "lines must contain at least one item")
    @Valid
    private List<ActionStockRequestLineRequest> lines;
}
