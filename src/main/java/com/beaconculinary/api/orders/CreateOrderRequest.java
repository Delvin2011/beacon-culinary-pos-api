package com.beaconculinary.api.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderRequest {
    // 1-2 entries, at most one per method; ACCOUNT is mutually exclusive with everything else —
    // enforced in OrderService, since these are cross-entry rules a bean-validation annotation
    // can't express cleanly.
    @NotEmpty(message = "payments cannot be empty")
    @Valid
    private List<OrderPaymentRequest> payments;

    @NotEmpty(message = "lines cannot be empty")
    @Valid
    private List<OrderLineRequest> lines;
}
