package com.beaconculinary.api.orders;

import lombok.Data;

/**
 * The optional complimentary object on POST /orders: the whole order is given away, with no
 * payment. reasonCode is taken as text and checked in OrderService, so an unknown value is
 * reported as COMPLIMENTARY_INVALID rather than a generic unreadable-body error.
 */
@Data
public class ComplimentaryRequest {
    /** STAFF_MEAL, MANAGEMENT, GUEST, TASTING or OTHER (OTHER needs a note). */
    private String reasonCode;
    private String note;
    /** Exactly one of a single-use manager-PIN token or a management session token, as for discounts. */
    private SaleDiscountRequest.Authorization authorization;
}
