package com.beaconculinary.api.reports;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A report's parameters after defaults and validation, typed per its spec. */
public class ReportParams {
    private final Map<String, Object> values;
    private final Paging paging;

    /** R3: the page a paged report should return. all = every row (exports). */
    public record Paging(int number, int size, boolean all) {
        public static final int DEFAULT_SIZE = 50;
        public static final int MAX_SIZE = 200;
        public static final Paging FIRST = new Paging(0, DEFAULT_SIZE, false);
        public static final Paging ALL = new Paging(0, Integer.MAX_VALUE, true);

        public long offset() {
            return all ? 0 : (long) number * size;
        }
    }

    ReportParams(Map<String, Object> values, Paging paging) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.paging = paging;
    }

    ReportParams(Map<String, Object> values) {
        this(values, Paging.FIRST);
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

    /** An ID parameter; null when unset. */
    public Long id(String name) {
        return (Long) values.get(name);
    }

    /** An INT parameter; null when unset. */
    public Integer integer(String name) {
        return (Integer) values.get(name);
    }

    public LocalDate date(String name) {
        return (LocalDate) values.get(name);
    }

    /** The LIMIT parameter as a row count; null for "ALL". */
    public Integer limit() {
        var limit = (String) values.get("limit");
        return limit == null || limit.equals("ALL") ? null : Integer.valueOf(limit);
    }

    public Paging paging() {
        return paging;
    }

    /** Echoed in meta.params: every parameter, with defaults applied (null when unset). */
    public Map<String, Object> asMap() {
        var echo = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> echo.put(key, value instanceof LocalDate date ? date.toString() : value));
        return echo;
    }
}
