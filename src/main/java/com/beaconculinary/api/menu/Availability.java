package com.beaconculinary.api.menu;

/** Derived from a meal's group: PLANNED (set meals, planned daily with portions) or ALWAYS
 * (light meals and beverages: unlimited, with an "Out today" switch, never planned). */
public enum Availability {
    PLANNED,
    ALWAYS
}
