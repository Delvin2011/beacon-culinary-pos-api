package com.beaconculinary.api.settings;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** T0-4a contract 4.2: the three first-trading-day rules, without a database. */
class FirstTradingDayRuleTest {
    private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);
    private static final LocalDate OCT_6 = LocalDate.of(2026, 10, 6);
    private static final LocalDate OCT_8 = LocalDate.of(2026, 10, 8);

    @Test
    void anythingGoesWithNoOrdersAndNoZ() {
        var none = new FirstTradingDayRule.Facts(null, null, null);
        assertThat(FirstTradingDayRule.check(OCT_5, none)).isNull();
        assertThat(FirstTradingDayRule.check(LocalDate.of(2030, 1, 1), none)).isNull();
        assertThat(FirstTradingDayRule.check(null, none)).isNull();
    }

    @Test
    void notAfterTheEarliestZ() {
        var facts = new FirstTradingDayRule.Facts(OCT_6, OCT_5, null);
        assertThat(FirstTradingDayRule.check(OCT_6, facts)).isNull();
        assertThat(FirstTradingDayRule.check(OCT_6.plusDays(1), facts))
                .isEqualTo("Must be on or before 6 Oct 2026, the first day with a Z reading.");
    }

    @Test
    void notBeforeTheEarliestOrderNorEmptyOnceThereAreOrders() {
        var facts = new FirstTradingDayRule.Facts(null, OCT_5, null);
        assertThat(FirstTradingDayRule.check(OCT_5, facts)).isNull();
        assertThat(FirstTradingDayRule.check(OCT_8, facts)).isNull();
        assertThat(FirstTradingDayRule.check(OCT_5.minusDays(1), facts))
                .isEqualTo("Must be on or after 5 Oct 2026, the first day with sales.");
        assertThat(FirstTradingDayRule.check(null, facts)).isEqualTo("Must be on or after 5 Oct 2026, the first day with sales.");
    }

    @Test
    void notSoEarlyThatAnUnclosedDayComesBeforeTheFirstZ() {
        // Sales from 5 Oct; 5 Oct has shifts but was never closed; Z 1 is for 8 Oct.
        var facts = new FirstTradingDayRule.Facts(OCT_8, OCT_5, OCT_5);
        assertThat(FirstTradingDayRule.check(OCT_5, facts))
                .isEqualTo("Must be after 5 Oct 2026, a trading day before the first Z reading that was never closed.");
        assertThat(FirstTradingDayRule.check(OCT_6, facts)).isNull();
        assertThat(FirstTradingDayRule.check(OCT_8, facts)).isNull();
    }
}
