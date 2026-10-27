package com.beaconculinary.api.board;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * board.done-display-minutes (env BOARD_DONE_DISPLAY_MINUTES, default 10): how long a DONE order
 * stays on the public board before it drops off. 0 removes it as soon as it's DONE. Read at
 * startup; a negative value stops the application from starting.
 */
@Component
public class PublicBoardSettings {
    private final int doneDisplayMinutes;

    public PublicBoardSettings(@Value("${board.done-display-minutes:10}") int doneDisplayMinutes) {
        if (doneDisplayMinutes < 0) {
            throw new IllegalStateException("board.done-display-minutes must be 0 or more, but was " + doneDisplayMinutes + ".");
        }
        this.doneDisplayMinutes = doneDisplayMinutes;
    }

    public int doneDisplayMinutes() {
        return doneDisplayMinutes;
    }

    public Duration doneDisplay() {
        return Duration.ofMinutes(doneDisplayMinutes);
    }
}
