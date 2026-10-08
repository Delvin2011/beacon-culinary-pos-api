package com.beaconculinary.api.reports;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One parameter in a report's catalogue entry; the frontend builds its filter bar from these.
 * defaultValue (JSON "default") for a DATE may be "today" or "today-N" (Johannesburg); options
 * lists an ENUM's allowed values; label is the filter bar's field label.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportParamSpec(
        String name,
        String label,
        ParamType type,
        @JsonProperty("default") String defaultValue,
        List<String> options) {

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
}
