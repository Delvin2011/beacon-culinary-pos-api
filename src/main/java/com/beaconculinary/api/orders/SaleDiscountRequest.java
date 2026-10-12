package com.beaconculinary.api.orders;

import lombok.Data;

import java.math.BigDecimal;

/**
 * The optional discount on POST /orders. type and reasonCode are taken as text and checked in
 * OrderService, so an unknown value is reported as DISCOUNT_INVALID rather than a generic
 * unreadable-body error.
 */
@Data
public class SaleDiscountRequest {
    /** PERCENTAGE or FIXED_AMOUNT. */
    private String type;

    /** The percentage (above 0, below 100) or the rand amount (above 0). */
    private BigDecimal value;

    /** STAFF, PROMOTION, MANAGER_GOODWILL, PRICING_CORRECTION or OTHER (OTHER needs a note). */
    private String reasonCode;

    private String note;

    private Authorization authorization;

    /** Exactly one of: a single-use manager-PIN token, or a management session token. */
    @Data
    public static class Authorization {
        private String authorizationToken;
        private String sessionToken;
    }
}
