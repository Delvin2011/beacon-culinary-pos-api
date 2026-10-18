package com.beaconculinary.api.reports;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One parameter in a report's catalogue entry; the frontend builds its filter bar from these.
 * defaultValue (JSON "default") for a DATE may be "today" or "today-N" (Johannesburg); options
 * lists an ENUM's or LIMIT's allowed values as plain strings, or an ID's choices as
 * {@link ParamOption}s; label is the filter bar's field label. required (R2) is true when a run
 * without the parameter is refused, and absent otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportParamSpec(
        String name,
        String label,
        ParamType type,
        @JsonProperty("default") String defaultValue,
        List<?> options,
        Boolean required) {

    public ReportParamSpec(String name, String label, ParamType type, String defaultValue, List<?> options) {
        this(name, label, type, defaultValue, options, null);
    }

    public static ReportParamSpec from() {
        return new ReportParamSpec("from", "From", ParamType.DATE, "today-6", null);
    }

    public static ReportParamSpec to() {
        return new ReportParamSpec("to", "To", ParamType.DATE, "today", null);
    }

    public static ReportParamSpec groupBy(List<GroupBy> allowed, GroupBy defaultValue) {
        return new ReportParamSpec("groupBy", "Group by", ParamType.ENUM, defaultValue.name(),
                allowed.stream().map(Enum::name).toList());
    }

    /** An ENUM whose first option is the default. */
    public static ReportParamSpec choice(String name, String label, String... options) {
        return new ReportParamSpec(name, label, ParamType.ENUM, options[0], List.of(options));
    }

    /** An optional ID picked from options; unset means "all". */
    public static ReportParamSpec id(String name, String label, List<ParamOption> options) {
        return new ReportParamSpec(name, label, ParamType.ID, null, options);
    }

    /** An ID the run can't do without. */
    public static ReportParamSpec requiredId(String name, String label, List<ParamOption> options) {
        return new ReportParamSpec(name, label, ParamType.ID, null, options, true);
    }

    /** "ALL" (the default) or a positive whole number of rows. */
    public static ReportParamSpec limit(String... suggestions) {
        return new ReportParamSpec("limit", "Show", ParamType.LIMIT, "ALL", List.of(suggestions));
    }

    /** Not named isRequired: Jackson would treat that as a second "required" getter. */
    public boolean requiresValue() {
        return Boolean.TRUE.equals(required);
    }

    /** The label of an ID option's value, or null when there is none. */
    public String optionLabel(Object value) {
        if (options == null || value == null) {
            return null;
        }
        return options.stream()
                .filter(ParamOption.class::isInstance)
                .map(ParamOption.class::cast)
                .filter(option -> option.value().equals(value.toString()))
                .map(ParamOption::label)
                .findFirst()
                .orElse(null);
    }
}
