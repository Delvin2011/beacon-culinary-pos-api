package com.beaconculinary.api.reports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports R3: builders for a STATEMENT's sections[]. Sections are plain maps so a stored Z
 * snapshot round-trips through JSON unchanged.
 * <ul>
 *   <li>KEY_VALUE: { key, type, title, items: [ { label, type, value, emphasis? } ] }; emphasis
 *   "TOTAL" marks a bold line.</li>
 *   <li>TABLE: { key, type, title, columns, rows, totals, slipColumns? }; slipColumns (at most 3
 *   column keys) are what the 80 mm slip shows. A TABLE without them is A4 only.</li>
 *   <li>SIGNATURE_LINES: { key, type, labels }.</li>
 * </ul>
 * printOn lists where a section prints: A4 and/or SLIP80 (both unless set).
 */
public final class Sections {
    public static final String A4 = "A4";
    public static final String SLIP80 = "SLIP80";

    private Sections() {
    }

    public static Map<String, Object> keyValue(String key, String title, List<Map<String, Object>> items) {
        var section = section(key, "KEY_VALUE", title);
        section.put("items", items);
        return section;
    }

    public static Map<String, Object> item(String label, ColumnType type, Object value) {
        var item = new LinkedHashMap<String, Object>();
        item.put("label", label);
        item.put("type", type.name());
        item.put("value", value);
        return item;
    }

    public static Map<String, Object> total(String label, ColumnType type, Object value) {
        var item = item(label, type, value);
        item.put("emphasis", "TOTAL");
        return item;
    }

    public static Map<String, Object> table(String key, String title, List<ReportColumn> columns,
                                            List<Map<String, Object>> rows, Map<String, Object> totals,
                                            String... slipColumns) {
        var section = section(key, "TABLE", title);
        section.put("columns", columns);
        section.put("rows", rows);
        section.put("totals", totals);
        if (slipColumns.length > 0) {
            if (slipColumns.length > 3) {
                throw new IllegalArgumentException("At most 3 slip columns");
            }
            section.put("slipColumns", List.of(slipColumns));
        } else {
            section.put("printOn", List.of(A4));
        }
        return section;
    }

    public static Map<String, Object> signatures(String key, String... labels) {
        var section = section(key, "SIGNATURE_LINES", null);
        section.put("labels", Arrays.asList(labels));
        return section;
    }

    public static List<Map<String, Object>> items() {
        return new ArrayList<>();
    }

    private static Map<String, Object> section(String key, String type, String title) {
        var section = new LinkedHashMap<String, Object>();
        section.put("key", key);
        section.put("type", type);
        if (title != null) {
            section.put("title", title);
        }
        return section;
    }
}
