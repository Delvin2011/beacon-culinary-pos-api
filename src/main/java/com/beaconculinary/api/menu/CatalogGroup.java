package com.beaconculinary.api.menu;

/** The fixed top level of the catalogue tree. A meal's group is its category's group. Set meals
 * are planned daily with portions; light meals and beverages are always available. */
public enum CatalogGroup {
    SET_MEALS,
    LIGHT_MEALS,
    BEVERAGES;

    public Availability availability() {
        return this == SET_MEALS ? Availability.PLANNED : Availability.ALWAYS;
    }
}
