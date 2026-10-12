package com.beaconculinary.api.orders;

/** A 400 the client must branch on, carrying a stable machine-readable code (e.g.
 * DISCOUNT_INVALID, DISCOUNT_TOO_LARGE, DISCOUNT_AFTER_PAYMENT_RETIRED,
 * DISCOUNTED_ORDER_NOT_REFUNDABLE). */
public class OrderRuleException extends RuntimeException {
    private final String code;

    public OrderRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    static OrderRuleException discountInvalid(String message) {
        return new OrderRuleException("DISCOUNT_INVALID", message);
    }

    static OrderRuleException discountTooLarge(String message) {
        return new OrderRuleException("DISCOUNT_TOO_LARGE", message);
    }
}
