package com.beaconculinary.api.menu;

/** Lifecycle of a {@link DailyMealOption} / {@link DailyComponentStock}. Only ever moves forward,
 * and only READY items are on sale in the POS. */
public enum DailyPlanItemStatus {
    /** Added to the plan; ingredients not yet reviewed. */
    PLANNED,
    /** Covered by a successful confirm-ingredient-requirements. */
    INGREDIENTS_REVIEWED,
    /** Reviewed, and an actual quantity has been recorded. On sale. */
    READY
}
