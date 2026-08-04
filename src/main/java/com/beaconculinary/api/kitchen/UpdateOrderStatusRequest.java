package com.beaconculinary.api.kitchen;

import com.beaconculinary.api.orders.OrderStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateOrderStatusRequest {
    @NotNull(message = "status is required")
    private OrderStatus status;
}
