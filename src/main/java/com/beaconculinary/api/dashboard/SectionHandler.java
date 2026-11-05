package com.beaconculinary.api.dashboard;

import java.util.Map;
import java.util.Set;

/** Computes one dashboard section. One bean per {@link DashboardSection}. */
public interface SectionHandler {
    DashboardSection section();

    /** Definition version of the section's payload; bump it when a figure's meaning changes. */
    default int definitionVersion() {
        return 1;
    }

    /** Query parameters the section takes besides range (they become part of its cache key). */
    default Set<String> extraParams() {
        return Set.of();
    }

    /** Whether the payload depends on who is asking beyond whether they see values (e.g. their
     * own requests): then the cache is per user. */
    default boolean perUser() {
        return false;
    }

    Result compute(Request request);

    record Request(DashboardCaller caller, DashboardRange range, Map<String, String> params) {
        public String param(String name) {
            return params.get(name);
        }
    }

    /** data is the section's payload (a Map, so absent and null fields stay distinct); link is
     * the report holding the same figures, or null. */
    record Result(Object data, ReportLink link) {
    }

    record ReportLink(String key, Map<String, Object> params) {
    }
}
