package com.beaconculinary.api.shifts;

/** Why a shift closed with a cash variance. The code is the API and database value; the label is
 * the display text the server sends in reports. THEFT_SUSPECTED keeps its code but reads
 * "Shortage", matching the till. */
public enum ShiftVarianceReasonCode {
    CASH_COUNTING_ERROR("Cash counting error"),
    THEFT_SUSPECTED("Shortage"),
    UNRECORDED_TRANSACTION("Unrecorded transaction"),
    OTHER("Other");

    private final String label;

    ShiftVarianceReasonCode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The label for a stored code, as read from the database; null stays null, and an unknown
     * code is returned as is rather than failing a report. */
    public static String labelOf(String code) {
        if (code == null) {
            return null;
        }
        try {
            return valueOf(code).label;
        } catch (IllegalArgumentException e) {
            return code;
        }
    }
}
