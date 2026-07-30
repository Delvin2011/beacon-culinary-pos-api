package com.beaconculinary.api.orders;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class OrderLineExtraRequest {
    @NotNull(message = "dailyComponentStockId is required")
    private Long dailyComponentStockId;

    @NotNull(message = "quantity is required")
    @Positive(message = "quantity must be positive")
    private Integer quantity;
}
