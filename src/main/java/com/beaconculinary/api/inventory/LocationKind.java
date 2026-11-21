package com.beaconculinary.api.inventory;

/**
 * T0-5c: what a stock location is for. Exactly one PRIMARY (receives every GRV and supplies every
 * issue; can be renamed, never deactivated). Any number of SECONDARY locations (request stock from
 * the primary; production and the sales stations draw from them).
 */
public enum LocationKind {
    PRIMARY,
    SECONDARY
}
