package com.beaconculinary.api.inventory;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdatePurchaseOrderStatusRequest {
    @NotNull(message = "status is required")
    private PurchaseOrderStatus status;
}
