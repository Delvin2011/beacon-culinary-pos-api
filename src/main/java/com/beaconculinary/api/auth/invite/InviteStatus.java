package com.beaconculinary.api.auth.invite;

/** T0-6a: an invite's state as the staff list shows it (NONE: the user has never had one). */
public enum InviteStatus {
    NONE, PENDING, EXPIRED, USED, REVOKED
}
