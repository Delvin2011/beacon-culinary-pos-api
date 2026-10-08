package com.beaconculinary.api.reports;

import java.util.List;

/** One row of GET /admin/reports. */
public record ReportCatalogueEntry(
        String key,
        String title,
        ReportCategory category,
        String description,
        ReportTemplate template,
        int definitionVersion,
        List<ReportParamSpec> params) {
}
