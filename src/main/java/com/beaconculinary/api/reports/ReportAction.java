package com.beaconculinary.api.reports;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Reports R3: a report-level button, e.g. Close Day. When enabled is false, disabledReasons
 * says why in plain language. confirm is the text of a confirmation step (null for none).
 * fields (omitted when empty) are inputs the viewer collects in that step and sends as the JSON
 * request body, keyed by name — e.g. the required note on Revoke sign-off.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportAction(String key, String label, String method, String path, String confirm, boolean enabled,
                           List<String> disabledReasons,
                           @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Field> fields) {

    public ReportAction(String key, String label, String method, String path, String confirm, boolean enabled,
                        List<String> disabledReasons) {
        this(key, label, method, path, confirm, enabled, disabledReasons, List.of());
    }

    /** A text input for the action's request body. */
    public record Field(String name, String label, boolean required) {
    }
}
