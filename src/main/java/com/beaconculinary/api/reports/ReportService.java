package com.beaconculinary.api.reports;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.common.ClockConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reports R1: the catalogue, running a report into the envelope, and exporting it. Nothing
 * here is report-specific — a new ReportDefinition bean is all a new report needs. */
@Service
public class ReportService {
    static final String VAT_NOTE = "Amounts include VAT.";
    static final List<String> TABLE_FORMATS = List.of("csv", "xlsx");
    static final List<String> STATEMENT_FORMATS = List.of("xlsx");

    private final ReportRegistry registry;
    private final ReportParamParser paramParser;
    private final ReportExporter exporter;
    private final AuthService authService;
    private final Clock clock;
    private final String businessName;

    public ReportService(ReportRegistry registry, ReportParamParser paramParser, ReportExporter exporter,
                         AuthService authService, Clock clock,
                         @Value("${app.business.name:Beacon Culinary}") String businessName) {
        this.registry = registry;
        this.paramParser = paramParser;
        this.exporter = exporter;
        this.authService = authService;
        this.clock = clock;
        this.businessName = businessName;
    }

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
        return run(allowedDefinition(key), rawParams, false);
    }

    private ReportEnvelope run(ReportDefinition definition, Map<String, String> rawParams, boolean allRows) {
        var specs = definition.params();
        var params = paramParser.parse(specs, rawParams, allRows);
        return wrap(definition, specs, params, definition.run(params));
    }

    /**
     * Wraps a result in the envelope with the shared meta. Public for callers that run a
     * definition themselves: Close Day (the Z snapshot) and GET /shifts/{id}/reading (the till,
     * which checks the shift's owner instead of the report's roles).
     */
    public ReportEnvelope wrap(ReportDefinition definition, ReportParams params, ReportResult result) {
        return wrap(definition, definition.params(), params, result);
    }

    private ReportEnvelope wrap(ReportDefinition definition, List<ReportParamSpec> specs, ReportParams params,
                                ReportResult result) {
        var notes = new ArrayList<>(definition.notes());
        if (!notes.contains(VAT_NOTE)) {
            notes.add(VAT_NOTE);
        }
        var echo = params.asMap();
        var statement = result.statement();
        var isStatement = definition.template() == ReportTemplate.STATEMENT;
        var meta = new ReportEnvelope.Meta(definition.key(), definition.title(), definition.category(),
                definition.definitionVersion(), clock.instant(), ClockConfig.BUSINESS_ZONE.getId(), "ZAR", true,
                echo, paramLabels(specs, echo), notes,
                isStatement ? STATEMENT_FORMATS : TABLE_FORMATS,
                statement == null ? null : statement.title(),
                statement == null ? null : statement.reference(),
                statement == null ? null : statement.interim(),
                isStatement ? new ReportEnvelope.Business(businessName) : null);
        return new ReportEnvelope(meta, result.columns(), result.rows(), result.totals(), result.checks(), result.page(),
                result.sections(), result.actions());
    }

    /** The option label of every ID parameter that is set, keyed by parameter name. */
    private static Map<String, String> paramLabels(List<ReportParamSpec> specs, Map<String, Object> values) {
        var labels = new LinkedHashMap<String, String>();
        for (var spec : specs) {
            var label = spec.optionLabel(values.get(spec.name()));
            if (label != null) {
                labels.put(spec.name(), label);
            }
        }
        return labels;
    }

    /** Same parameters as a run, but every row (paging is ignored); the file is built from the
     * same envelope the screen shows. Statements export as XLSX only. */
    @Transactional(readOnly = true)
    public ReportExporter.ExportFile export(String key, Map<String, String> rawParams) {
        var definition = allowedDefinition(key);
        var format = format(rawParams.get("format"));
        if (definition.template() == ReportTemplate.STATEMENT && format != ReportExporter.Format.XLSX) {
            throw ReportParamException.of("format", "Statements export as xlsx only.");
        }
        return exporter.export(run(definition, rawParams, true), format);
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
