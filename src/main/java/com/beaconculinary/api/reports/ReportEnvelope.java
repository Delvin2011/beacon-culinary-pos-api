package com.beaconculinary.api.reports;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reports R1 §1.2: the one response contract every report returns. A TABLE report fills
 * columns/rows/totals; a STATEMENT report (R3) fills sections instead (see {@link Sections})
 * and leaves those three null. actions (R3) are report-level buttons such as Close Day; rows
 * may carry their own under "_actions" (see {@link RowAction}).
 */
public record ReportEnvelope(
        Meta meta,
        List<ReportColumn> columns,
        List<Map<String, Object>> rows,
        Map<String, Object> totals,
        List<ReportCheck> checks,
        Page page,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Map<String, Object>> sections,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ReportAction> actions) {

    /** paramLabels (R2) gives the display label of each ID parameter that is set, e.g.
     * { "accountId": "Acme Logistics" }; omitted when empty. R3: statementTitle, reference
     * ("Z-0001"), interim and business are set on statements; exportFormats on every report. */
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
            List<String> notes,
            List<String> exportFormats,
            @JsonInclude(JsonInclude.Include.NON_NULL) String statementTitle,
            @JsonInclude(JsonInclude.Include.NON_NULL) String reference,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean interim,
            @JsonInclude(JsonInclude.Include.NON_NULL) Business business) {
    }

    public record Business(String name) {
    }

    /** Set only when the rows are a slice. paged = false: a limited list ("Showing N of M",
     * R2's limit); paged = true (R3): the client may request other pages with the page and size
     * query parameters. number is zero-based; totalRows counts every matching row. */
    public record Page(int number, int size, long totalRows, boolean paged) {
        public Page(int number, int size, long totalRows) {
            this(number, size, totalRows, false);
        }
    }
}
