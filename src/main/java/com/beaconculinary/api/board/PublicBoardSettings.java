package com.beaconculinary.api.board;

import com.beaconculinary.api.settings.SiteSettings;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * board.done-display-minutes: how long a DONE order stays on the public board before it drops off.
 * 0 removes it as soon as it's DONE. A site setting since T0-4a (default from configuration, env
 * BOARD_DONE_DISPLAY_MINUTES, 10; a negative default stops the app, see SiteSettingDefaults).
 * Read on every call.
 */
@Component
public class PublicBoardSettings {
    private final SiteSettings siteSettings;

    public PublicBoardSettings(SiteSettings siteSettings) {
        this.siteSettings = siteSettings;
    }

    public int doneDisplayMinutes() {
        return siteSettings.boardDoneDisplayMinutes();
    }

    public Duration doneDisplay() {
        return Duration.ofMinutes(doneDisplayMinutes());
    }
}
