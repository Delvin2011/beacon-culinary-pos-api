package com.beaconculinary.api.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@PaymentsRequiredForSale
public class CreateOrderRequest {
    // O1: optional client-generated idempotency key. The same key again returns the existing
    // order (200); with a different payload, 409 IDEMPOTENCY_CONFLICT. Required when
    // pos.require-client-order-id is true.
    private UUID clientOrderId;

    // 1-2 entries, at most one per method; ACCOUNT is mutually exclusive with everything else —
    // enforced in OrderService, since these are cross-entry rules a bean-validation annotation
    // can't express cleanly. Required for a sale (@PaymentsRequiredForSale); a complimentary
    // order has none.
    @Valid
    private List<OrderPaymentRequest> payments;

    @NotEmpty(message = "lines cannot be empty")
    @Valid
    private List<OrderLineRequest> lines;

    // Optional manager-approved discount at checkout; payments must then equal the discounted total.
    private SaleDiscountRequest discount;

    // Optional: the whole order is complimentary (no payment, manager-approved). Can't be
    // combined with a discount or payments.
    private ComplimentaryRequest complimentary;
}
