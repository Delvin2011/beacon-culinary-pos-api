package com.beaconculinary.api.board;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicBoardSettingsTest {
    @Test
    void zeroAndPositiveMinutesAreAccepted() {
        assertThat(new PublicBoardSettings(0).doneDisplay()).isEqualTo(Duration.ZERO);
        assertThat(new PublicBoardSettings(15).doneDisplay()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void negativeMinutesAreRejected() {
        assertThatThrownBy(() -> new PublicBoardSettings(-1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("board.done-display-minutes");
    }
}
