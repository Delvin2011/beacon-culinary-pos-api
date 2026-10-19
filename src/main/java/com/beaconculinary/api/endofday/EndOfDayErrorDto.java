package com.beaconculinary.api.endofday;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The 409 body for end-of-day conflicts: { error, code, blockers?, zNumber? }. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EndOfDayErrorDto(String error, String code, List<DayCloseRules.Blocker> blockers, Integer zNumber) {
    public static EndOfDayErrorDto of(EndOfDayConflictException ex) {
        return new EndOfDayErrorDto(ex.getMessage(), ex.getCode(), ex.getBlockers(), ex.getZNumber());
    }
}
