package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardFacts;
import com.beaconculinary.api.dashboard.DashboardFacts.StationLoad;
import com.beaconculinary.api.dashboard.DashboardFormat;
import com.beaconculinary.api.dashboard.DashboardPermission;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.DashboardSettings;
import com.beaconculinary.api.dashboard.DashboardSettings.Feature;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import com.beaconculinary.api.shifts.ShiftCashCalculator;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Right now: open work per station (the station status rows; before the bar, every open order
 * is the kitchen's and bar is null), orders ready to collect, and who holds the till. "Wait" is
 * minutes since the order was paid (orders are paid when rung); ready counts from when the order
 * became DONE. onTheTill is null with no open shift and absent without REPORTS_CASH.
 */
@Component
@AllArgsConstructor
public class NowSection implements SectionHandler {
    private final DashboardFacts dashboardFacts;
    private final TradingCalendar calendar;
    private final DashboardSettings settings;
    private final ShiftCashCalculator shiftCashCalculator;

    @Override
    public DashboardSection section() {
        return DashboardSection.NOW;
    }

    @Override
    public Result compute(Request request) {
        var now = calendar.now();
        var nowUtc = calendar.nowUtc();
        var bar = settings.enabled(Feature.BAR_STATION);
        var load = bar ? dashboardFacts.stationLoad(nowUtc) : dashboardFacts.orderLoad(nowUtc);

        var data = new LinkedHashMap<String, Object>();
        data.put("kitchen", station(load, "KITCHEN", now));
        data.put("bar", bar ? station(load, "BAR", now) : null);

        var ready = dashboardFacts.readyToCollect(nowUtc);
        var readyMap = new LinkedHashMap<String, Object>();
        readyMap.put("count", ready.count());
        readyMap.put("oldestMinutes", ready.count() == 0 ? null : DashboardFormat.minutesSince(ready.oldestDoneUtc(), now));
        data.put("readyToCollect", readyMap);

        if (request.caller().has(DashboardPermission.REPORTS_CASH)) {
            data.put("onTheTill", till());
        }
        return new Result(data, null);
    }

    private static Map<String, Object> station(List<StationLoad> load, String station, Instant now) {
        long pending = 0;
        long inProgress = 0;
        LocalDateTime oldest = null;
        for (var row : load) {
            if (!row.station().equals(station)) {
                continue;
            }
            if (row.status().equals("PENDING")) {
                pending += row.count();
            } else {
                inProgress += row.count();
            }
            if (oldest == null || row.oldestPaidUtc().isBefore(oldest)) {
                oldest = row.oldestPaidUtc();
            }
        }
        var map = new LinkedHashMap<String, Object>();
        map.put("pending", pending);
        map.put("inProgress", inProgress);
        map.put("open", pending + inProgress);
        map.put("longestWaitMinutes", oldest == null ? null : DashboardFormat.minutesSince(oldest, now));
        return map;
    }

    private Map<String, Object> till() {
        var open = dashboardFacts.openShifts(calendar.nowUtc());
        if (open.isEmpty()) {
            return null;
        }
        var shift = open.get(0);
        var opened = DashboardFormat.local(shift.openedAtUtc());
        var till = new LinkedHashMap<String, Object>();
        till.put("shiftId", shift.shiftId());
        till.put("owner", DashboardFormat.shortName(shift.ownerName()));
        till.put("since", opened.toLocalDate().equals(calendar.today()) ? DashboardFormat.time(shift.openedAtUtc())
                : DashboardFormat.day(opened.toLocalDate()) + " " + DashboardFormat.time(shift.openedAtUtc()));
        till.put("expectedCash", DashboardFormat.money(
                shiftCashCalculator.compute(shift.shiftId(), shift.openingFloat()).expectedCash()));
        return till;
    }
}
