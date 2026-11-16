package com.beaconculinary.api.orders;

/** O1: 409 IDEMPOTENCY_CONFLICT. The idempotency key was already used for a different request
 * (other payload, other order, or another cashier's sale), so nothing was created or returned. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
