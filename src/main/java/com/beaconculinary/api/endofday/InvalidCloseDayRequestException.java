package com.beaconculinary.api.endofday;

import java.util.Map;

/** O3: an invalid Close Day override request; rendered as a 400 field map, e.g. {"reason": "..."}. */
public class InvalidCloseDayRequestException extends RuntimeException {
    private final Map<String, String> errors;

    public InvalidCloseDayRequestException(Map<String, String> errors) {
        super("Invalid Close Day request");
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
