package com.beaconculinary.api.orders;

import com.beaconculinary.api.menu.Station;
import com.beaconculinary.api.users.UserRefDto;

import java.time.Instant;

/** One station's part of an order on order reads (stations[]). updatedBy is null until someone
 * moves it on. */
public record OrderStationDto(Station station, OrderStatus status, Instant updatedAt, UserRefDto updatedBy) {
}
