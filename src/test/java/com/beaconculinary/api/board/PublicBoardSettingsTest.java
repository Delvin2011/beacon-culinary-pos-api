package com.beaconculinary.api.board;

import com.beaconculinary.api.settings.SiteSettingsFixture;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The minutes come from the site setting (T0-4a); a negative default is refused by SiteSettingDefaults (SiteSettingRegistryTest). */
class PublicBoardSettingsTest {
    @Test
    void zeroAndPositiveMinutesAreAccepted() {
        assertThat(new PublicBoardSettings(SiteSettingsFixture.settings("board.done-display-minutes", "0")).doneDisplay())
                .isEqualTo(Duration.ZERO);
        assertThat(new PublicBoardSettings(SiteSettingsFixture.settings("board.done-display-minutes", "15")).doneDisplay())
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theDefaultIsTenMinutes() {
        assertThat(new PublicBoardSettings(SiteSettingsFixture.settings()).doneDisplayMinutes()).isEqualTo(10);
    }
}
