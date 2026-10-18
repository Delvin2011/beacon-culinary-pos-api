package com.beaconculinary.api.reports;

import java.util.List;
import java.util.Map;

/** What a definition computes; the service wraps it in the envelope with the shared meta. page
 * (R2) is set only when the rows are a limited slice; totals still cover every matching row. */
public record ReportResult(
        List<ReportColumn> columns,
        List<Map<String, Object>> rows,
        Map<String, Object> totals,
        List<ReportCheck> checks,
        ReportEnvelope.Page page) {

    public ReportResult(List<ReportColumn> columns, List<Map<String, Object>> rows, Map<String, Object> totals,
                        List<ReportCheck> checks) {
        this(columns, rows, totals, checks, null);
    }
}
