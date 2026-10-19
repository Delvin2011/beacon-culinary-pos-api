package com.beaconculinary.api.endofday;

import java.util.List;

/** A 409 with a stable code: SHIFT_NOT_CLOSED, ALREADY_SIGNED_OFF, NOT_SIGNED_OFF,
 * DAY_ALREADY_CLOSED, DAY_CLOSED, or CLOSE_DAY_BLOCKED (with every blocker). */
public class EndOfDayConflictException extends RuntimeException {
    private final String code;
    private final List<DayCloseRules.Blocker> blockers;
    private final Integer zNumber;

    public EndOfDayConflictException(String code, String message) {
        this(code, message, null, null);
    }

    public EndOfDayConflictException(String code, String message, List<DayCloseRules.Blocker> blockers, Integer zNumber) {
        super(message);
        this.code = code;
        this.blockers = blockers;
        this.zNumber = zNumber;
    }

    public String getCode() {
        return code;
    }

    public List<DayCloseRules.Blocker> getBlockers() {
        return blockers;
    }

    public Integer getZNumber() {
        return zNumber;
    }
}
