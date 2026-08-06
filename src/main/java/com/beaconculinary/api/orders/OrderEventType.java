package com.beaconculinary.api.orders;

public enum OrderEventType {
    ORDER_CREATED,
    STATUS_CHANGED,
    // Stage 2.6 — an EXTRAS_ONLY adjustment on a still-active order. Kitchen stream only; the
    // order's status hasn't changed so the public board has nothing to react to.
    ORDER_UPDATED
}
