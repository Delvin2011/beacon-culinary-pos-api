package com.beaconculinary.api.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CreatePurchaseOrderRequest {
    @NotBlank(message = "supplierName is required")
    private String supplierName;

    @Valid
    private List<PurchaseOrderLineRequest> lines = List.of();
}
