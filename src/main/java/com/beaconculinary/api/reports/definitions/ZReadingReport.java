package com.beaconculinary.api.reports.definitions;

import com.beaconculinary.api.endofday.CloseDaySettings;
import com.beaconculinary.api.endofday.ZReadings;
import com.beaconculinary.api.reports.EndOfDayStatements;
import com.beaconculinary.api.reports.ParamOption;
import com.beaconculinary.api.reports.ParamType;
import com.beaconculinary.api.reports.ReportCategory;
import com.beaconculinary.api.reports.ReportCheck;
import com.beaconculinary.api.reports.ReportDefinition;
import com.beaconculinary.api.reports.ReportParamSpec;
import com.beaconculinary.api.reports.ReportParams;
import com.beaconculinary.api.reports.ReportResult;
import com.beaconculinary.api.reports.ReportTemplate;
import com.beaconculinary.api.reports.Sections;
import com.beaconculinary.api.reports.ColumnType;
import com.beaconculinary.api.users.Role;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reports R3 §6: a closed day's numbered Z Reading. It returns the snapshot stored at Close
 * Day, never a recalculation, so later changes to the data can't alter it.
 */
@Component
@AllArgsConstructor
public class ZReadingReport implements ReportDefinition {
    /** Stored with each Z; bump when the snapshot's shape changes. */
    public static final int Z_DEFINITION_VERSION = 1;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    private final ZReadings zReadings;
    private final CloseDaySettings closeDaySettings;
    private final ObjectMapper objectMapper;

    @Override
    public String key() {
        return "z-reading";
    }

    @Override
    public String title() {
        return "Z Reading";
    }

    @Override
    public ReportCategory category() {
        return ReportCategory.DAILY_STATEMENTS;
    }

    @Override
    public String description() {
        return "A closed trading day's numbered Z Reading, exactly as it was when the day was closed.";
    }

    @Override
    public ReportTemplate template() {
        return ReportTemplate.STATEMENT;
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.ADMIN);
    }

    /** Every Z, newest first ("Z-0001 · Mon 5 Oct 2026"); the default is the latest. */
    @Override
    public List<ReportParamSpec> params() {
        var all = zReadings.all();
        var options = all.stream().map(z -> ParamOption.of(z.zNumber(), z.reference() + " · " + DAY.format(z.tradingDay())))
                .toList();
        var latest = all.isEmpty() ? null : Integer.toString(all.get(0).zNumber());
        return List.of(new ReportParamSpec("zNumber", "Z reading", ParamType.ID, latest, options));
    }

    @Override
    public int definitionVersion() {
        return Z_DEFINITION_VERSION;
    }

    @Override
    public List<String> notes() {
        return XReadingReport.DAY_NOTES;
    }

    @Override
    public ReportResult run(ReportParams params) {
        var zNumber = params.id("zNumber");
        if (zNumber == null) {
            var items = Sections.items();
            items.add(Sections.item("Status", ColumnType.TEXT, "No day has been closed yet."));
            if (closeDaySettings.firstTradingDay() != null) {
                items.add(Sections.item("Z numbering starts", ColumnType.DATE, closeDaySettings.firstTradingDay()));
            }
            return ReportResult.statement(new ReportResult.Statement(EndOfDayStatements.Z_TITLE, null, false),
                    List.of(Sections.keyValue("empty", null, items)), List.of(), List.of());
        }
        var snapshot = zReadings.snapshot(zNumber.intValue()).orElseThrow();
        try {
            var reader = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            JsonNode root = reader.readTree(snapshot);
            List<Map<String, Object>> sections = reader.forType(new TypeReference<List<Map<String, Object>>>() { })
                    .readValue(root.get("sections"));
            List<ReportCheck> checks = reader.forType(new TypeReference<List<ReportCheck>>() { }).readValue(root.get("checks"));
            var meta = root.get("meta");
            return ReportResult.statement(new ReportResult.Statement(meta.get("statementTitle").asText(),
                    meta.get("reference").asText(), false), sections, checks, List.of());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Z " + zNumber + " has an unreadable snapshot.", e);
        }
    }

    /** The stored form of a Z envelope. */
    public String toSnapshot(Object envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Couldn't store the Z snapshot.", e);
        }
    }
}
