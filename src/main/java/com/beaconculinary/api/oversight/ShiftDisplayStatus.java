package com.beaconculinary.api.oversight;

/** POS Oversight A4: derived from shift state and the Africa/Johannesburg calendar day, never
 * from live presence. OVERDUE = still open from an earlier day, which blocks today's opening. */
public enum ShiftDisplayStatus {
    ON_SHIFT,
    CLOSED,
    OVERDUE
}
