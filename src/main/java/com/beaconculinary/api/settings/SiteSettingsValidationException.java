package com.beaconculinary.api.settings;

import java.util.Map;

/** T0-4a: a 400 field map, keyed by setting key (contract section 4). */
public class SiteSettingsValidationException extends RuntimeException {
    private final transient Map<String, String> errors;

    public SiteSettingsValidationException(Map<String, String> errors) {
        super("Site settings are not valid: " + errors.keySet());
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
