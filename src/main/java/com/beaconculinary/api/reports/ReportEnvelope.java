package com.beaconculinary.api.reports;

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
            List<String> notes) {
    }

    /** null for non-paginated reports (all of R1). */
    public record Page(int number, int size, long totalRows) {
    }
}
