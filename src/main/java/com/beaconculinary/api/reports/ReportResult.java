package com.beaconculinary.api.reports;

import java.util.List;
import java.util.Map;

/** What a definition computes; the service wraps it in the envelope with the shared meta. page
 * (R2) is set only when the rows are a slice; totals still cover every matching row. A
 * STATEMENT (R3) sets sections instead of columns/rows/totals, plus its statement meta and any
 * report-level actions. */
public record ReportResult(
        List<ReportColumn> columns,
        List<Map<String, Object>> rows,
        Map<String, Object> totals,
        List<ReportCheck> checks,
        ReportEnvelope.Page page,
        List<Map<String, Object>> sections,
        List<ReportAction> actions,
        Statement statement) {

    /** Statement-only meta: the title as printed, a reference such as "Z-0001", and interim. */
    public record Statement(String title, String reference, boolean interim) {
    }

    public ReportResult(List<ReportColumn> columns, List<Map<String, Object>> rows, Map<String, Object> totals,
                        List<ReportCheck> checks) {
        this(columns, rows, totals, checks, null, null, null, null);
    }

    public ReportResult(List<ReportColumn> columns, List<Map<String, Object>> rows, Map<String, Object> totals,
                        List<ReportCheck> checks, ReportEnvelope.Page page) {
        this(columns, rows, totals, checks, page, null, null, null);
    }

    public static ReportResult statement(Statement statement, List<Map<String, Object>> sections, List<ReportCheck> checks,
                                         List<ReportAction> actions) {
        return new ReportResult(null, null, null, checks, null, sections, actions, statement);
    }
}
