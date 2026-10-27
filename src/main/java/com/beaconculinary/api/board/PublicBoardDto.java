package com.beaconculinary.api.board;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/** serverTime lets the board correct for its own clock when timing removeAt; doneDisplayMinutes
 * is the configured window, so the front end never hard-codes it. */
@Data
@AllArgsConstructor
public class PublicBoardDto {
    private List<PublicOrderDto> orders;
    private Instant serverTime;
    private int doneDisplayMinutes;
}
