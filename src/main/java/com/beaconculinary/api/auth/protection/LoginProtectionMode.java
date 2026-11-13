package com.beaconculinary.api.auth.protection;

/** How login protection behaves. See docs/2026-11-11-login-protection.md. */
public enum LoginProtectionMode {
    /** Nothing is checked or recorded. Login behaves exactly as before WS1-1. */
    OFF,
    /** Every attempt is recorded and evaluated; would-be blocks are flagged and logged, never applied. */
    MONITOR,
    /** Blocks and locks are applied. Not available until WS1-1 Phase 2; refused at startup. */
    ENFORCE
}
