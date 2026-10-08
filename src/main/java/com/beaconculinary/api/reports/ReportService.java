package com.beaconculinary.api.reports;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.common.ClockConfig;
import lombok.AllArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reports R1: the catalogue, running a report into the envelope, and exporting it. Nothing
 * here is report-specific — a new ReportDefinition bean is all a new report needs. */
@Service
@AllArgsConstructor
public class ReportService {
    static final String VAT_NOTE = "Amounts include VAT.";

    private final ReportRegistry registry;
    private final ReportParamParser paramParser;
    private final ReportExporter exporter;
    private final AuthService authService;
    private final Clock clock;

    /** Only the reports the caller may run. */
    public List<ReportCatalogueEntry> catalogue() {
        var role = authService.getCurrentUser().getRole();
        return registry.all().stream()
                .filter(definition -> definition.allowedRoles().contains(role))
                .map(definition -> new ReportCatalogueEntry(definition.key(), definition.title(), definition.category(),
                        definition.description(), definition.template(), definition.definitionVersion(), definition.params()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReportEnvelope run(String key, Map<String, String> rawParams) {
        var definition = allowedDefinition(key);
        var params = paramParser.parse(definition.params(), rawParams);
        var result = definition.run(params);

        var notes = new ArrayList<>(definition.notes());
        if (!notes.contains(VAT_NOTE)) {
            notes.add(VAT_NOTE);
        }
        var meta = new ReportEnvelope.Meta(definition.key(), definition.title(), definition.category(),
                definition.definitionVersion(), clock.instant(), ClockConfig.BUSINESS_ZONE.getId(), "ZAR", true,
                params.asMap(), notes);
        return new ReportEnvelope(meta, result.columns(), result.rows(), result.totals(), result.checks(), null);
    }

    /** Same parameters as a run; the file is built from the same envelope the screen shows. */
    @Transactional(readOnly = true)
    public ReportExporter.ExportFile export(String key, Map<String, String> rawParams) {
        allowedDefinition(key);
        var format = format(rawParams.get("format"));
        return exporter.export(run(key, rawParams), format);
    }

    private ReportDefinition allowedDefinition(String key) {
        var definition = registry.find(key).orElseThrow(() -> new ReportNotFoundException(key));
        if (!definition.allowedRoles().contains(authService.getCurrentUser().getRole())) {
            throw new AccessDeniedException("Not allowed to run report " + key + ".");
        }
        return definition;
    }

    private static ReportExporter.Format format(String raw) {
        try {
            return ReportExporter.Format.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ReportParamException.of("format", "Format must be csv or xlsx.");
        }
    }
}
