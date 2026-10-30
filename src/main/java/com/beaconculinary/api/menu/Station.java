package com.beaconculinary.api.menu;

/** Where a meal is prepared. Set meals and light meals: KITCHEN. Beverages: BAR (hot, by default)
 * or NONE (cold: handed over at payment, so no display and no status). Only KITCHEN and BAR have
 * an order_station_status row. */
public enum Station {
    KITCHEN,
    BAR,
    NONE;

    public boolean prepares() {
        return this != NONE;
    }
}
