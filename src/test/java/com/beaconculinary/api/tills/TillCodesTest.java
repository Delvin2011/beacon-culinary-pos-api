package com.beaconculinary.api.tills;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TillCodesTest {
    @Test
    void lowestFree_startsAtT1() {
        assertThat(TillCodes.lowestFree(List.of())).isEqualTo("T1");
    }

    @Test
    void lowestFree_fillsTheFirstGap() {
        assertThat(TillCodes.lowestFree(List.of("T1", "T3"))).isEqualTo("T2");
        assertThat(TillCodes.lowestFree(List.of("T2", "T3"))).isEqualTo("T1");
        assertThat(TillCodes.lowestFree(List.of("T1", "T2", "T3"))).isEqualTo("T4");
    }

    @Test
    void lowestFree_ignoresCustomCodesNullsAndCase() {
        assertThat(TillCodes.lowestFree(Arrays.asList("FRONT", null, "t1", "BAR-2"))).isEqualTo("T2");
    }

    @Test
    void normalise_trimsAndUpperCases() {
        assertThat(TillCodes.normalise("  t2 ")).isEqualTo("T2");
        assertThat(TillCodes.normalise(null)).isNull();
    }

    @Test
    void isValid_allowsLettersDigitsAndHyphens_upToTenCharacters() {
        assertThat(TillCodes.isValid("T1")).isTrue();
        assertThat(TillCodes.isValid("FRONT-1")).isTrue();
        assertThat(TillCodes.isValid("ABCDEFGHIJ")).isTrue();
        assertThat(TillCodes.isValid("ABCDEFGHIJK")).isFalse();
        assertThat(TillCodes.isValid("")).isFalse();
        assertThat(TillCodes.isValid("T 1")).isFalse();
        assertThat(TillCodes.isValid("T_1")).isFalse();
        assertThat(TillCodes.isValid("t1")).isFalse();
        assertThat(TillCodes.isValid(null)).isFalse();
    }
}
