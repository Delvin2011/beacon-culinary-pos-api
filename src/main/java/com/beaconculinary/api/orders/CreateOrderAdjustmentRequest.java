package com.beaconculinary.api.orders;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateOrderAdjustmentRequest {
    // Missing or CANCEL -> Stage 2.6 behavior, unchanged (auto-derives VOID/REFUND from order
    // status, per scope). DISCOUNT triggers the Stage 3.2 discount path below.
    private OrderAdjustmentRequestedAction requestedAction;

    // Required only when requestedAction is missing/CANCEL — enforced in
    // OrderAdjustmentService, since it doesn't apply to DISCOUNT (whole-order only).
    private OrderAdjustmentScope scope;

    @NotNull(message = "reasonCode is required")
    private OrderAdjustmentReasonCode reasonCode;

    // Required only when reasonCode = OTHER — enforced in OrderAdjustmentService, since that's
    // a cross-field rule a bean-validation annotation can't express cleanly.
    private String note;

    // Stage 2.6's single-use, 60-second token. Either this or sessionToken must be supplied and
    // valid — enforced in OrderAdjustmentService/AdminAuthorizationService, since "at least one
    // of two fields" isn't expressible with a single-field bean-validation annotation.
    private String authorizationToken;

    // Stage 3.3's longer-lived, reusable management-session token — an alternative to
    // authorizationToken that isn't consumed on use.
    private String sessionToken;

    // Required only when requestedAction = DISCOUNT — enforced in OrderAdjustmentService.
    private DiscountType discountType;
    private BigDecimal discountValue;
}
