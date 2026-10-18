package com.beaconculinary.api.reports;

/** Reports R2: one choice of an ID parameter, e.g. { "value": "2", "label": "Lunch" }. */
public record ParamOption(String value, String label) {
    public static ParamOption of(long id, String label) {
        return new ParamOption(Long.toString(id), label);
    }
}
