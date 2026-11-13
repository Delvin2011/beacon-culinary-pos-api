package com.beaconculinary.api.devices;

/** How POS till registration is applied. See docs/2026-11-13-device-registration-api-contract.md. */
public enum DeviceTrustMode {
    /** No checks; behaviour as before. Tills can still be registered, and their last-seen time is kept. */
    OFF,
    /** A valid registration is recognised and recorded; missing or invalid ones are allowed and logged. */
    OPTIONAL,
    /** Gated requests without a valid registration are refused (403 DEVICE_NOT_REGISTERED). */
    REQUIRED
}
