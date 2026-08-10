package com.beaconculinary.api.orders;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderPaymentRequest {
    @NotNull(message = "method is required")
    private PaymentMethod method;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be positive")
    private BigDecimal amount;

    // CASH only — enforced in OrderService, since that's a cross-field rule a bean-validation
    // annotation can't express cleanly.
    private BigDecimal amountTendered;

    // CARD only — enforced in OrderService.
    private String cardReference;

    // ACCOUNT only — enforced in OrderService.
    private Long accountId;
}
