package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardFacts;
import com.beaconculinary.api.dashboard.DashboardFormat;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.DashboardSettings;
import com.beaconculinary.api.dashboard.DashboardSettings.Feature;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import com.beaconculinary.api.reports.ReportRange;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Today's waits, from the status events: paid (rung) to ready per station, ready to collected,
 * and the longest paid-to-ready wait. A station is ready at its own DONE event; orders from
 * before station events (no station event at all) use the order's DONE event, as the kitchen's.
 * Medians are computed here from today's rows. barMedianMinutes is null before the bar exists.
 */
@Component
@AllArgsConstructor
public class WaitingSection implements SectionHandler {
    private final DashboardFacts dashboardFacts;
    private final TradingCalendar calendar;
    private final DashboardSettings settings;

    @Override
    public DashboardSection section() {
        return DashboardSection.WAITING;
    }

    @Override
    public Result compute(Request request) {
        var today = calendar.today();
        var bar = settings.enabled(Feature.BAR_STATION);
        var kitchenWaits = new ArrayList<BigDecimal>();
        var barWaits = new ArrayList<BigDecimal>();
        var collectWaits = new ArrayList<BigDecimal>();
        BigDecimal longest = null;
        LocalDateTime longestAt = null;

        for (var order : dashboardFacts.timelines(new ReportRange(today, today), calendar.nowUtc())) {
            var kitchenReady = order.kitchenDoneUtc() != null ? order.kitchenDoneUtc()
                    : order.barDoneUtc() == null ? order.doneUtc() : null;
            var readyAt = new LocalDateTime[]{kitchenReady, bar ? order.barDoneUtc() : null};
            var waits = List.of(kitchenWaits, barWaits);
            for (int station = 0; station < readyAt.length; station++) {
                if (readyAt[station] == null) {
                    continue;
                }
                var wait = DashboardFormat.minutes(order.paidUtc(), readyAt[station]);
                waits.get(station).add(wait);
                if (longest == null || wait.compareTo(longest) > 0) {
                    longest = wait;
                    longestAt = order.paidUtc();
                }
            }
            if (order.doneUtc() != null && order.collectedUtc() != null) {
                collectWaits.add(DashboardFormat.minutes(order.doneUtc(), order.collectedUtc()));
            }
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("kitchenMedianMinutes", median(kitchenWaits));
        data.put("barMedianMinutes", bar ? median(barWaits) : null);
        data.put("collectMedianMinutes", median(collectWaits));
        if (longest == null) {
            data.put("longest", null);
        } else {
            var map = new LinkedHashMap<String, Object>();
            map.put("minutes", longest);
            map.put("at", DashboardFormat.time(longestAt));
            data.put("longest", map);
        }
        return new Result(data, null);
    }

    static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        var sorted = values.stream().sorted().toList();
        var middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP);
    }
}
