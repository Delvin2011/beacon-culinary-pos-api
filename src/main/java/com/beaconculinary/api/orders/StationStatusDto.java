package com.beaconculinary.api.orders;

import com.beaconculinary.api.menu.Station;

/** Another station's progress on an order, shown on a station display (otherStations). */
public record StationStatusDto(Station station, OrderStatus status) {
}
