package com.beaconculinary.api.reports;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Reports R1 §1.2: the one response contract every report returns. */
public record ReportEnvelope(
        Meta meta,
        List<ReportColumn> columns,
        List<Map<String, Object>> rows,
        Map<String, Object> totals,
        List<ReportCheck> checks,
        Page page) {

    /** paramLabels (R2) gives the display label of each ID parameter that is set, e.g.
     * { "accountId": "Acme Logistics" }, for headings and export title blocks; omitted when empty. */
    public record Meta(
            String key,
            String title,
            ReportCategory category,
            int definitionVersion,
            Instant generatedAt,
            String timezone,
            String currency,
            boolean amountsInclVat,
            Map<String, Object> params,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> paramLabels,
            List<String> notes) {
    }

    /** Set only when the rows are a limited slice (R2): number is the zero-based page, size the
     * row limit, totalRows every matching row. null for unlimited reports (all of R1). */
    public record Page(int number, int size, long totalRows) {
    }
}
