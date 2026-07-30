package com.beaconculinary.api.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OrderLineRequest {
    @NotNull(message = "dailyMealOptionId is required")
    private Long dailyMealOptionId;

    @NotNull(message = "quantity is required")
    @Positive(message = "quantity must be positive")
    private Integer quantity;

    @Valid
    private List<OrderLineExtraRequest> extras = new ArrayList<>();
}
