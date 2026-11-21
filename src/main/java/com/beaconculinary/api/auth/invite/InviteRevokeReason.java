package com.beaconculinary.api.auth.invite;

/** T0-6a: why an invite was closed without being used. */
public enum InviteRevokeReason {
    /** A newer invite for the same user. */
    REPLACED,
    /** An admin revoked it. */
    REVOKED
}
