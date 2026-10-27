package com.beaconculinary.api.shifts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShiftVarianceReasonCodeTest {
    @Test
    void labels() {
        assertThat(ShiftVarianceReasonCode.labelOf("CASH_COUNTING_ERROR")).isEqualTo("Cash counting error");
        assertThat(ShiftVarianceReasonCode.labelOf("THEFT_SUSPECTED")).isEqualTo("Shortage");
        assertThat(ShiftVarianceReasonCode.labelOf("UNRECORDED_TRANSACTION")).isEqualTo("Unrecorded transaction");
        assertThat(ShiftVarianceReasonCode.labelOf("OTHER")).isEqualTo("Other");
    }

    @Test
    void codeIsUnchanged() {
        assertThat(ShiftVarianceReasonCode.THEFT_SUSPECTED.name()).isEqualTo("THEFT_SUSPECTED");
    }

    @Test
    void nullAndUnknownCodes() {
        assertThat(ShiftVarianceReasonCode.labelOf(null)).isNull();
        assertThat(ShiftVarianceReasonCode.labelOf("SOMETHING_NEW")).isEqualTo("SOMETHING_NEW");
    }
}
