package com.beaconculinary.api.reports;

import java.util.List;
import java.util.Map;

/** What a definition computes; the service wraps it in the envelope with the shared meta. */
public record ReportResult(
        List<ReportColumn> columns,
        List<Map<String, Object>> rows,
        Map<String, Object> totals,
        List<ReportCheck> checks) {
}
