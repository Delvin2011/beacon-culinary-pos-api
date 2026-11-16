package com.beaconculinary.api.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O1: pos.require-client-order-id (env POS_REQUIRE_CLIENT_ORDER_ID, default false). When true, a
 * sale without clientOrderId is refused with 400 CLIENT_ORDER_ID_REQUIRED and a void or refund
 * without clientRequestId with 400 CLIENT_REQUEST_ID_REQUIRED. Off until every till sends them.
 */
@Component
public class PosSettings {
    private final boolean requireClientOrderId;

    public PosSettings(@Value("${pos.require-client-order-id:false}") boolean requireClientOrderId) {
        this.requireClientOrderId = requireClientOrderId;
    }

    public boolean requireClientOrderId() {
        return requireClientOrderId;
    }
}
