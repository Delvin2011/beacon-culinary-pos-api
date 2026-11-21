package com.beaconculinary.api.auth.invite;

/** T0-6a: why an invite was issued. */
public enum InvitePurpose {
    /** The customer's first administrator, issued by the operator's first-admin command. */
    FIRST_ADMIN,
    /** A new staff member sets their own credentials. */
    STAFF_SETUP,
    /** An admin resets a staff member's password and/or PIN. */
    RESET
}
