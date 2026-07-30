package com.beaconculinary.api.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateOrderRequest {
    @NotNull(message = "amountTendered is required")
    @DecimalMin(value = "0.00", message = "amountTendered cannot be negative")
    private BigDecimal amountTendered;

    @NotEmpty(message = "lines cannot be empty")
    @Valid
    private List<OrderLineRequest> lines;
}
