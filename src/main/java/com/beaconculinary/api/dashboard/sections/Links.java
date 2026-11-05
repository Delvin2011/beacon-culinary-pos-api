package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.SectionHandler.ReportLink;
import com.beaconculinary.api.reports.ReportRange;

import java.util.LinkedHashMap;

/** "View report" links: the report key and the parameters that give the same figures. */
final class Links {
    private Links() {
    }

    static ReportLink report(String key, ReportRange range, Object... extra) {
        var params = new LinkedHashMap<String, Object>();
        params.put("from", range.from().toString());
        params.put("to", range.to().toString());
        for (int i = 0; i + 1 < extra.length; i += 2) {
            params.put((String) extra[i], extra[i + 1]);
        }
        return new ReportLink(key, params);
    }
}
