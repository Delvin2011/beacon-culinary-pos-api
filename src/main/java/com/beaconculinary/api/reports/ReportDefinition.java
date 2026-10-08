package com.beaconculinary.api.reports;

import com.beaconculinary.api.users.Role;

import java.util.List;
import java.util.Set;

/**
 * Reports R1 §1.3: one report. Register it by making it a Spring bean — the registry, catalogue,
 * controller, export and frontend viewer pick it up with no other change. Reports compose the
 * shared definitions in {@link ReportFacts} and never re-derive a business rule.
 */
public interface ReportDefinition {
    String key();

    String title();

    ReportCategory category();

    String description();

    default ReportTemplate template() {
        return ReportTemplate.TABLE;
    }

    /** Who may see and run this report; enforced per definition, not only by URL pattern. */
    Set<Role> allowedRoles();

    List<ReportParamSpec> params();

    int definitionVersion();

    /** Plain-language definitions for the "About this report" panel. "Amounts include VAT." is
     * appended to every report by the service. */
    List<String> notes();

    ReportResult run(ReportParams params);
}
