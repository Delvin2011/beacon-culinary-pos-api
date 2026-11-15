package com.beaconculinary.api.devices;

import java.util.Map;

/** Field errors, returned as a 400 map of field to message (as elsewhere). */
public class DeviceValidationException extends RuntimeException {
    private final Map<String, String> errors;

    public DeviceValidationException(Map<String, String> errors) {
        super("Invalid device details: " + errors.keySet());
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
