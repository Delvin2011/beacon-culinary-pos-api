package com.beaconculinary.api.sessions;

/**
 * O2 R7/R8: what a session is for (user_sessions.kind).
 *
 * <ul>
 *   <li>POS: a CASHIER or ADMIN PIN sign-in, or any non-station sign-in on a registered till. One
 *       active POS session per user and per till; can be locked, idled out and replaced.</li>
 *   <li>STATION: a PIN sign-in by KITCHEN, BARISTA or HEAD_CHEF (kitchen and bar screens). Not limited.</li>
 *   <li>BACK_OFFICE: a password sign-in on a browser that isn't a registered till. Not limited.</li>
 * </ul>
 */
public enum SessionKind {
    POS,
    STATION,
    BACK_OFFICE
}
