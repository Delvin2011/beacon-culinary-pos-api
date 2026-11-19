package com.beaconculinary.api.settings;

import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.tills.TillProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T0-4a: SiteSettings reads saved values over defaults, caches them for 30 s, and ignores bad saved values. */
class SiteSettingsCacheTest {
    private static final Instant T0 = Instant.parse("2026-11-19T08:00:00Z");

    /** A store over a list, counting reads. */
    static class FakeStore extends SiteSettingsStore {
        final List<Saved> rows = new ArrayList<>();
        int reads;

        FakeStore() {
            super(null);
        }

        @Override
        public List<Saved> findAll() {
            reads++;
            return List.copyOf(rows);
        }

        void save(String key, String value) {
            rows.removeIf(row -> row.key().equals(key));
            rows.add(new Saved(key, value, LocalDateTime.of(2026, 11, 19, 8, 0), "Canteen Admin"));
        }
    }

    private FakeStore store;
    private MutableClock clock;
    private SiteSettings settings;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        clock = new MutableClock(Clock.fixed(T0, ZoneOffset.UTC));
        var defaults = new SiteSettingDefaults(new TillProperties(), SiteSettingRegistryTest.offline(), "0.15", "2026-10-12",
                "10", "50.00", "3.0", "25", "12:30", "06:00-15:00");
        settings = new SiteSettings(store, defaults, clock);
    }

    @Test
    void defaultsWhenNothingIsSaved() {
        assertThat(settings.vatStandardRate()).isEqualByComparingTo("0.15");
        assertThat(settings.sessionIdleExpiryMinutes()).isEqualTo(30);
        assertThat(settings.tillsRequireRegistered()).isFalse();
        assertThat(settings.isSaved(SiteSetting.VAT_STANDARD_RATE)).isFalse();
        assertThat(settings.effective()).hasSize(15);
    }

    @Test
    void aSavedValueWinsOverTheDefault() {
        store.save("vat.standard-rate", "0.16");
        store.save("reports.close-day.first-trading-day", null);
        assertThat(settings.vatStandardRate()).isEqualByComparingTo("0.16");
        assertThat(settings.isSaved(SiteSetting.VAT_STANDARD_RATE)).isTrue();
        assertThat(settings.firstTradingDay()).isNull();
        assertThat(settings.isSaved(SiteSetting.FIRST_TRADING_DAY)).isTrue();
    }

    @Test
    void anotherInstancesWriteIsSeenAfterThirtySecondsNotBefore() {
        assertThat(settings.boardDoneDisplayMinutes()).isEqualTo(10);
        store.save("board.done-display-minutes", "3");

        clock.setInstant(T0.plusSeconds(29));
        assertThat(settings.boardDoneDisplayMinutes()).isEqualTo(10);
        assertThat(store.reads).isEqualTo(1);

        clock.setInstant(T0.plusSeconds(30));
        assertThat(settings.boardDoneDisplayMinutes()).isEqualTo(3);
        assertThat(store.reads).isEqualTo(2);
    }

    @Test
    void invalidateAppliesAWriteOnThisInstanceAtOnce() {
        assertThat(settings.boardDoneDisplayMinutes()).isEqualTo(10);
        store.save("board.done-display-minutes", "3");
        settings.invalidate();
        assertThat(settings.boardDoneDisplayMinutes()).isEqualTo(3);
    }

    @Test
    void aClockMovedBackwardsExpiresTheCache() {
        assertThat(settings.boardDoneDisplayMinutes()).isEqualTo(10);
        store.save("board.done-display-minutes", "3");
        clock.setInstant(T0.minusSeconds(1));
        assertThat(settings.boardDoneDisplayMinutes()).isEqualTo(3);
    }

    @Test
    void unknownUnreadableAndOutOfLimitValuesAreIgnored() {
        store.save("no.such.setting", "1");
        store.save("vat.standard-rate", "fifteen");
        store.save("board.done-display-minutes", "-5");
        store.save("pos.offline.max-offline-hours", "40");   // above grant-max-hours (18)
        store.save("pos.session.idle-lock-minutes", "7");
        assertThat(settings.vatStandardRate()).isEqualByComparingTo("0.15");
        assertThat(settings.boardDoneDisplayMinutes()).isEqualTo(10);
        assertThat(settings.offlineMaxOfflineHours()).isEqualTo(6);
        assertThat(settings.sessionIdleLockMinutes()).isEqualTo(7);
    }

    @Test
    void savedValuesBreakingACrossRuleFallBackToTheirDefaults() {
        store.save("pos.session.idle-lock-minutes", "40");    // default expiry is 30
        store.save("pos.offline.account-cap", "200.00");
        assertThat(settings.sessionIdleLockMinutes()).isEqualTo(5);
        assertThat(settings.sessionIdleExpiryMinutes()).isEqualTo(30);
        assertThat(settings.offlineAccountCap()).isEqualTo(new BigDecimal("200.00"));
    }
}
