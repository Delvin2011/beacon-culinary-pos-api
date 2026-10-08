package com.beaconculinary.api.reports;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A report's parameters after defaults and validation, typed per its spec. */
public class ReportParams {
    private final Map<String, Object> values;

    ReportParams(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static ReportParams of(Map<String, Object> values) {
        return new ReportParams(values);
    }

    public ReportRange range() {
        return new ReportRange((LocalDate) values.get("from"), (LocalDate) values.get("to"));
    }

    public GroupBy groupBy() {
        return GroupBy.valueOf((String) values.get("groupBy"));
    }

    public String string(String name) {
        return (String) values.get(name);
    }

    public boolean bool(String name) {
        return Boolean.TRUE.equals(values.get(name));
    }

    public Long userId(String name) {
        return (Long) values.get(name);
    }

    /** Echoed in meta.params: every parameter, with defaults applied (null when unset). */
    public Map<String, Object> asMap() {
        var echo = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> echo.put(key, value instanceof LocalDate date ? date.toString() : value));
        return echo;
    }
}
