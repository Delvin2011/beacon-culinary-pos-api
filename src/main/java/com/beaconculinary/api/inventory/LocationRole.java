package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.Station;

/**
 * T0-5c: a job that uses one SECONDARY location, held in location_assignments (V115). PRODUCTION
 * is where daily planning's issue sheet sends ingredients; each STATION_* is where that station's
 * sale-time stock (light meals) is deducted from.
 */
public enum LocationRole {
    PRODUCTION,
    STATION_KITCHEN,
    STATION_BAR,
    STATION_NONE;

    public static LocationRole forStation(Station station) {
        return switch (station == null ? Station.KITCHEN : station) {
            case KITCHEN -> STATION_KITCHEN;
            case BAR -> STATION_BAR;
            case NONE -> STATION_NONE;
        };
    }
}
