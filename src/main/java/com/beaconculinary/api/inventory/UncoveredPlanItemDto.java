package com.beaconculinary.api.inventory;

import java.util.List;

/**
 * A planned item the ingredient requirements can't account for, so nothing will be issued for
 * it: a meal with no components linked (NO_COMPONENTS), or a meal or extra whose component has
 * no recipe, or a recipe with no ingredient lines (NO_RECIPE, naming those components). A meal
 * with some components covered and some not is listed too, with only the missing ones. Bought-in
 * items legitimately have no recipe, so this is a warning for the planner, not an error.
 *
 * @param type       MEAL (a daily meal option) or EXTRA (a daily component stock entry)
 * @param planItemId the daily_meal_options or daily_component_stock id
 * @param name       the plan entry's name (meal) or the component's name (extra)
 * @param components the components without a usable recipe; empty for NO_COMPONENTS
 */
public record UncoveredPlanItemDto(Type type, Long planItemId, String name, Reason reason, List<String> components) {
    public enum Type {
        MEAL,
        EXTRA
    }

    public enum Reason {
        NO_COMPONENTS,
        NO_RECIPE
    }
}
