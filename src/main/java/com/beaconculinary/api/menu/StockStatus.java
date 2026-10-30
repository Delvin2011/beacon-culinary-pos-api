package com.beaconculinary.api.menu;

/** Light meal stock, from what the Kitchen holds against one unit's recipe. */
public enum StockStatus {
    /** Not a light meal, or no recipe lines: never warned about or deducted. */
    NOT_TRACKED,
    /** The Kitchen can make none. */
    OUT,
    /** The Kitchen can make fewer units than the meal's low-stock level. */
    LOW,
    OK
}
