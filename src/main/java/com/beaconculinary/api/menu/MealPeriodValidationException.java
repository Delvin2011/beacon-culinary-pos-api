package com.beaconculinary.api.menu;

import java.util.Map;

/** T0-5a: a 400 field map (contract section 4.1). */
public class MealPeriodValidationException extends RuntimeException {
    private final transient Map<String, String> errors;

    public MealPeriodValidationException(Map<String, String> errors) {
        super("Meal period is not valid: " + errors.keySet());
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
